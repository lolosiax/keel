/*
 * MariaDB 数据库能力包（`kind: capability`，`provides: ["database"]`）。
 *
 * 后端链接 MariaDB Connector/C（`libmariadb`），实现 keel_database.h 的 ABI v2：
 * exec / query / next / result_free / begin / commit / rollback / last_error /
 * last_insert_id / ping。
 *
 * 线程模型：每个 Worker 线程首次进入时调用 `mysql_thread_init`；全局库初始化
 * 走平台 once。连接池懒建，最多 `max_connections` 条；事务句柄独占一条连接。
 * 错误与 last_insert_id 都是**线程局部**的，避免两个插件并发时互相覆盖。
 */

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
#define WIN32_LEAN_AND_MEAN
#include <winsock2.h>
#include <windows.h>
#endif

#include <mysql.h>

#include "keel_abi.h"
#include "keel_database.h"
#include "keel_platform.h"

#define DB_ID "database.mariadb"
#define DB_PROVIDES "database"
#define DB_VERSION "0.1.0"

#define DB_KIND_POOL 1
#define DB_KIND_TX 2
#define DB_ERROR_SIZE 512
#define DB_DEFAULT_PORT 3306
#define DB_BUFFER_LIMIT (64u * 1024u * 1024u)
#define DB_MAX_COLUMNS 1024u

#if defined(_MSC_VER)
#define DB_TLS __declspec(thread)
#else
#define DB_TLS __thread
#endif

typedef struct DbHandleBase {
    int kind;
} DbHandleBase;

typedef struct DbConn {
    MYSQL *mysql;
    int in_use;
} DbConn;

typedef struct DbPool {
    DbHandleBase base;
    KeelDbApi api;
    char *url;
    char *url_username;
    char *url_password;
    char *username;
    char *password;
    char *host;
    char *database;
    unsigned int port;
    uint32_t max_connections;
    uint32_t connect_timeout_ms;
    keel_mutex_t lock;
    keel_cond_t cond;
    DbConn **conns;
} DbPool;

typedef struct DbTx {
    DbHandleBase base;
    DbPool *pool;
    MYSQL *mysql;
} DbTx;

typedef struct DbResult {
    DbPool *pool;
    MYSQL *mysql;
    MYSQL_STMT *stmt;
    MYSQL_RES *meta;
    MYSQL_FIELD *fields;
    MYSQL_BIND *binds;
    uint32_t columns;
    int pooled;
    KeelDbValue *row;
    uint8_t **buffers;
    unsigned long *capacities;
    unsigned long *lengths;
    my_bool *is_null;
    my_bool *truncated;
    int64_t *ints;
    double *floats;
} DbResult;

static DB_TLS char tls_error[DB_ERROR_SIZE];
static DB_TLS uint64_t tls_last_insert_id;

/* ------------------------------------------------------------------------- */
/* 初始化                                                                     */
/* ------------------------------------------------------------------------- */

#ifdef _WIN32
static INIT_ONCE g_library_once = INIT_ONCE_STATIC_INIT;

static BOOL CALLBACK library_init_cb(PINIT_ONCE once, PVOID param, PVOID *context) {
    (void) once;
    (void) param;
    (void) context;
    mysql_library_init(0, NULL, NULL);
    return TRUE;
}

static void ensure_library(void) {
    InitOnceExecuteOnce(&g_library_once, library_init_cb, NULL, NULL);
}
#else
#include <pthread.h>

static pthread_once_t g_library_once = PTHREAD_ONCE_INIT;

static void library_init_cb(void) {
    mysql_library_init(0, NULL, NULL);
}

static void ensure_library(void) {
    pthread_once(&g_library_once, library_init_cb);
}
#endif

/** 每个使用 Connector/C 的线程调用一次；重复调用无副作用。 */
static void ensure_thread(void) {
    mysql_thread_init();
}

static void set_error(const char *text) {
    snprintf(tls_error, DB_ERROR_SIZE, "%s", text != NULL ? text : "");
}

static void set_mysql_error(MYSQL *mysql, const char *prefix) {
    snprintf(
        tls_error,
        DB_ERROR_SIZE,
        "%s: (%u) %s",
        prefix,
        (unsigned) mysql_errno(mysql),
        mysql_error(mysql)
    );
}

static void set_stmt_error(MYSQL_STMT *stmt, const char *prefix) {
    snprintf(
        tls_error,
        DB_ERROR_SIZE,
        "%s: (%u) %s",
        prefix,
        (unsigned) mysql_stmt_errno(stmt),
        mysql_stmt_error(stmt)
    );
}

static char *copy_string(const char *value) {
    if (value == NULL) return NULL;
    size_t size = strlen(value) + 1;
    char *copy = (char *) malloc(size);
    if (copy != NULL) memcpy(copy, value, size);
    return copy;
}

static DbPool *pool_of(DbHandleBase *base) {
    if (base == NULL) return NULL;
    if (base->kind == DB_KIND_POOL) return (DbPool *) base;
    if (base->kind == DB_KIND_TX) return ((DbTx *) base)->pool;
    return NULL;
}

static DbHandleBase *base_of(KeelDbHandle *handle) {
    if (handle == NULL) return NULL;
    DbHandleBase *base = (DbHandleBase *) handle;
    if (base->kind != DB_KIND_POOL && base->kind != DB_KIND_TX) return NULL;
    return base;
}

/* ------------------------------------------------------------------------- */
/* URL 解析                                                                   */
/* ------------------------------------------------------------------------- */

/**
 * 解析 `mysql://user:pass@host:port/db`、`mariadb://` 或裸 `host:port/db`。
 *
 * 用户名 / 密码以配置里的显式字段为准，URL 里的只作为回退。
 */
static int parse_url(DbPool *pool, const char *url) {
    const char *start = url;
    const char *scheme = strstr(start, "://");
    if (scheme != NULL) start = scheme + 3;

    char *authority = copy_string(start);
    if (authority == NULL) return 0;

    char *query = strchr(authority, '?');
    if (query != NULL) *query = '\0';

    char *database = NULL;
    char *slash = strrchr(authority, '/');
    if (slash != NULL) {
        database = slash + 1;
        *slash = '\0';
    }

    char *hostport = authority;
    char *at = strrchr(authority, '@');
    if (at != NULL) {
        *at = '\0';
        char *colon = strchr(authority, ':');
        if (colon != NULL) {
            *colon = '\0';
            pool->url_username = copy_string(authority);
            pool->url_password = copy_string(colon + 1);
        } else {
            pool->url_username = copy_string(authority);
        }
        hostport = at + 1;
    }

    char *host = NULL;
    char *port = NULL;
    if (hostport[0] == '[') {
        char *close = strchr(hostport, ']');
        if (close == NULL) {
            free(authority);
            return 0;
        }
        *close = '\0';
        host = copy_string(hostport + 1);
        if (close[1] == ':') port = copy_string(close + 2);
    } else {
        char *colon = strrchr(hostport, ':');
        if (colon != NULL && colon[1] != '\0' && strspn(colon + 1, "0123456789") == strlen(colon + 1)) {
            *colon = '\0';
            port = copy_string(colon + 1);
        }
        host = copy_string(hostport);
    }

    if (host == NULL || host[0] == '\0') {
        free(host);
        free(port);
        free(authority);
        return 0;
    }

    pool->host = host;
    pool->database = (database != NULL && database[0] != '\0') ? copy_string(database) : NULL;
    pool->port = (unsigned int) DB_DEFAULT_PORT;
    if (port != NULL) {
        long parsed = strtol(port, NULL, 10);
        if (parsed > 0 && parsed <= 65535) pool->port = (unsigned int) parsed;
    }
    free(port);
    free(authority);
    return 1;
}

/* ------------------------------------------------------------------------- */
/* 连接池                                                                     */
/* ------------------------------------------------------------------------- */

static MYSQL *connect_new(DbPool *pool) {
    ensure_thread();

    MYSQL *mysql = mysql_init(NULL);
    if (mysql == NULL) {
        set_error("mysql_init 失败");
        return NULL;
    }

    unsigned int timeout = pool->connect_timeout_ms > 0 ? pool->connect_timeout_ms : 3000;
    mysql_options(mysql, MYSQL_OPT_CONNECT_TIMEOUT, &timeout);
    mysql_options(mysql, MYSQL_OPT_READ_TIMEOUT, &timeout);
    mysql_options(mysql, MYSQL_OPT_WRITE_TIMEOUT, &timeout);
    mysql_options(mysql, MYSQL_SET_CHARSET_NAME, "utf8mb4");
    my_bool local_infile = 0;
    mysql_options(mysql, MYSQL_OPT_LOCAL_INFILE, &local_infile);

    const char *user = (pool->username != NULL && pool->username[0] != '\0') ? pool->username : NULL;
    const char *password =
        (pool->password != NULL && pool->password[0] != '\0') ? pool->password : NULL;
    const char *database =
        (pool->database != NULL && pool->database[0] != '\0') ? pool->database : NULL;

    if (mysql_real_connect(
            mysql,
            pool->host,
            user,
            password,
            database,
            pool->port,
            NULL,
            0
        ) == NULL) {
        set_mysql_error(mysql, "连接 MariaDB 失败");
        mysql_close(mysql);
        return NULL;
    }

    mysql_set_character_set(mysql, "utf8mb4");
    /* 时间列统一按 UTC 解释，驱动返回的 TIMESTAMP 就是 epoch 微秒。 */
    mysql_query(mysql, "SET time_zone = '+00:00'");
    return mysql;
}

/**
 * 借一条连接。
 *
 * 池里没有空闲且未达上限时现场建立（锁外连接）；等待超时返回失败。
 */
static KeelStatus acquire_conn(DbPool *pool, MYSQL **out) {
    uint32_t timeout = pool->connect_timeout_ms > 0 ? pool->connect_timeout_ms : 3000;
    uint32_t waited = 0;

    for (;;) {
        keel_mutex_lock(&pool->lock);
        for (uint32_t index = 0; index < pool->max_connections; index++) {
            DbConn *conn = pool->conns[index];
            if (conn != NULL && !conn->in_use) {
                conn->in_use = 1;
                MYSQL *mysql = conn->mysql;
                keel_mutex_unlock(&pool->lock);
                *out = mysql;
                return KEEL_OK;
            }
        }

        uint32_t created = 0;
        for (uint32_t index = 0; index < pool->max_connections; index++) {
            if (pool->conns[index] != NULL) created++;
        }
        if (created < pool->max_connections) {
            DbConn *slot = (DbConn *) calloc(1, sizeof(DbConn));
            if (slot == NULL) {
                keel_mutex_unlock(&pool->lock);
                set_error("连接槽内存不足");
                return KEEL_ERR_FAILED;
            }
            slot->in_use = 1;
            uint32_t position = 0;
            while (position < pool->max_connections && pool->conns[position] != NULL) position++;
            pool->conns[position] = slot;
            keel_mutex_unlock(&pool->lock);

            MYSQL *mysql = connect_new(pool);
            if (mysql == NULL) {
                keel_mutex_lock(&pool->lock);
                pool->conns[position] = NULL;
                keel_mutex_unlock(&pool->lock);
                free(slot);
                return KEEL_ERR_FAILED;
            }

            keel_mutex_lock(&pool->lock);
            slot->mysql = mysql;
            keel_mutex_unlock(&pool->lock);
            *out = mysql;
            return KEEL_OK;
        }

        uint32_t remaining = timeout > waited ? timeout - waited : 0;
        int rc = keel_cond_wait(&pool->cond, &pool->lock, remaining);
        keel_mutex_unlock(&pool->lock);
        if (rc != 0) {
            set_error("等待空闲连接超时");
            return KEEL_ERR_FAILED;
        }
        waited += remaining;
    }
}

static void release_conn(DbPool *pool, MYSQL *mysql) {
    keel_mutex_lock(&pool->lock);
    for (uint32_t index = 0; index < pool->max_connections; index++) {
        DbConn *conn = pool->conns[index];
        if (conn != NULL && conn->mysql == mysql) {
            conn->in_use = 0;
            break;
        }
    }
    keel_cond_broadcast(&pool->cond);
    keel_mutex_unlock(&pool->lock);
}

/* ------------------------------------------------------------------------- */
/* 参数绑定                                                                   */
/* ------------------------------------------------------------------------- */

typedef struct DbParams {
    MYSQL_BIND *binds;
    my_bool *is_null;
    my_bool *errors;
    unsigned long *lengths;
    int64_t *ints;
    double *doubles;
    signed char *bools;
    MYSQL_TIME *times;
} DbParams;

static void free_params(DbParams *params, uint32_t count) {
    (void) count;
    free(params->binds);
    free(params->is_null);
    free(params->errors);
    free(params->lengths);
    free(params->ints);
    free(params->doubles);
    free(params->bools);
    free(params->times);
    memset(params, 0, sizeof(*params));
}

static void civil_from_days(int64_t days, int *year, unsigned int *month, unsigned int *day) {
    int64_t z = days + 719468;
    int64_t era = (z >= 0 ? z : z - 146096) / 146097;
    unsigned int doe = (unsigned int) (z - era * 146097);
    unsigned int yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    int64_t y = (int64_t) yoe + era * 400;
    unsigned int doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    unsigned int mp = (5 * doy + 2) / 153;
    unsigned int d = doy - (153 * mp + 2) / 5 + 1;
    int m = (int) mp + (mp < 10 ? 3 : -9);
    *year = (int) (y + (m <= 2));
    *month = (unsigned int) m;
    *day = d;
}

static int64_t days_from_civil(int year, unsigned int month, unsigned int day) {
    int y = year - (month <= 2 ? 1 : 0);
    int era = (y >= 0 ? y : y - 399) / 400;
    unsigned int yoe = (unsigned int) (y - era * 400);
    int mp = (int) month + (month > 2 ? -3 : 9);
    unsigned int doy = (unsigned int) ((153 * mp + 2) / 5 + (int) day - 1);
    unsigned int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    return (int64_t) era * 146097 + (int64_t) doe - 719468;
}

static void timestamp_to_time(int64_t micros, MYSQL_TIME *out) {
    int64_t seconds = micros / 1000000;
    int64_t fraction = micros % 1000000;
    if (fraction < 0) {
        fraction += 1000000;
        seconds--;
    }
    int64_t days = seconds / 86400;
    int64_t rest = seconds % 86400;
    if (rest < 0) {
        rest += 86400;
        days--;
    }
    int year = 0;
    unsigned int month = 1;
    unsigned int day = 1;
    civil_from_days(days, &year, &month, &day);

    memset(out, 0, sizeof(*out));
    out->year = (unsigned int) year;
    out->month = month;
    out->day = day;
    out->hour = (unsigned int) (rest / 3600);
    out->minute = (unsigned int) ((rest % 3600) / 60);
    out->second = (unsigned int) (rest % 60);
    out->second_part = (unsigned long) fraction;
    out->time_type = MYSQL_TIMESTAMP_DATETIME;
}

static int prepare_params(
    MYSQL_STMT *stmt,
    const KeelDbValue *values,
    uint32_t count,
    DbParams *params
) {
    memset(params, 0, sizeof(*params));
    if (count == 0) return 1;

    params->binds = (MYSQL_BIND *) calloc(count, sizeof(MYSQL_BIND));
    params->is_null = (my_bool *) calloc(count, sizeof(my_bool));
    params->errors = (my_bool *) calloc(count, sizeof(my_bool));
    params->lengths = (unsigned long *) calloc(count, sizeof(unsigned long));
    params->ints = (int64_t *) calloc(count, sizeof(int64_t));
    params->doubles = (double *) calloc(count, sizeof(double));
    params->bools = (signed char *) calloc(count, sizeof(signed char));
    params->times = (MYSQL_TIME *) calloc(count, sizeof(MYSQL_TIME));

    if (params->binds == NULL || params->is_null == NULL || params->errors == NULL ||
        params->lengths == NULL || params->ints == NULL || params->doubles == NULL ||
        params->bools == NULL || params->times == NULL) {
        free_params(params, count);
        set_error("参数绑定内存不足");
        return 0;
    }

    for (uint32_t index = 0; index < count; index++) {
        const KeelDbValue *value = &values[index];
        MYSQL_BIND *bind = &params->binds[index];
        bind->is_null = &params->is_null[index];
        bind->length = &params->lengths[index];
        bind->error = &params->errors[index];

        switch (value->type) {
            case KEEL_DB_NULL:
                params->is_null[index] = 1;
                bind->buffer_type = MYSQL_TYPE_NULL;
                break;

            case KEEL_DB_INT:
                params->ints[index] = 0;
                if (value->bytes != NULL && value->len >= sizeof(int64_t)) {
                    memcpy(&params->ints[index], value->bytes, sizeof(int64_t));
                }
                bind->buffer_type = MYSQL_TYPE_LONGLONG;
                bind->buffer = &params->ints[index];
                bind->buffer_length = sizeof(int64_t);
                bind->is_unsigned = 0;
                break;

            case KEEL_DB_FLOAT:
                params->doubles[index] = 0;
                if (value->bytes != NULL && value->len >= sizeof(double)) {
                    memcpy(&params->doubles[index], value->bytes, sizeof(double));
                }
                bind->buffer_type = MYSQL_TYPE_DOUBLE;
                bind->buffer = &params->doubles[index];
                bind->buffer_length = sizeof(double);
                break;

            case KEEL_DB_BOOL:
                params->bools[index] = (value->bytes != NULL && value->bytes[0] != 0) ? 1 : 0;
                bind->buffer_type = MYSQL_TYPE_TINY;
                bind->buffer = &params->bools[index];
                bind->buffer_length = 1;
                break;

            case KEEL_DB_TIMESTAMP: {
                int64_t micros = 0;
                if (value->bytes != NULL && value->len >= sizeof(int64_t)) {
                    memcpy(&micros, value->bytes, sizeof(int64_t));
                }
                timestamp_to_time(micros, &params->times[index]);
                bind->buffer_type = MYSQL_TYPE_DATETIME;
                bind->buffer = &params->times[index];
                bind->buffer_length = sizeof(MYSQL_TIME);
                break;
            }

            case KEEL_DB_TEXT:
            case KEEL_DB_DECIMAL:
            case KEEL_DB_JSON:
            case KEEL_DB_UUID:
                bind->buffer_type = MYSQL_TYPE_STRING;
                bind->buffer = (void *) value->bytes;
                bind->buffer_length = value->len;
                params->lengths[index] = value->len;
                break;

            case KEEL_DB_BLOB:
                bind->buffer_type = MYSQL_TYPE_BLOB;
                bind->buffer = (void *) value->bytes;
                bind->buffer_length = value->len;
                params->lengths[index] = value->len;
                break;

            default:
                free_params(params, count);
                set_error("参数类型不受支持");
                return 0;
        }
    }

    if (mysql_stmt_bind_param(stmt, params->binds) != 0) {
        set_stmt_error(stmt, "绑定参数失败");
        free_params(params, count);
        return 0;
    }
    return 1;
}

/* ------------------------------------------------------------------------- */
/* 语句执行                                                                   */
/* ------------------------------------------------------------------------- */

static KeelStatus prepare_execute(
    MYSQL *mysql,
    const char *sql,
    uint32_t sql_len,
    const KeelDbValue *params,
    uint32_t param_count,
    MYSQL_STMT **out
) {
    MYSQL_STMT *stmt = mysql_stmt_init(mysql);
    if (stmt == NULL) {
        set_mysql_error(mysql, "mysql_stmt_init 失败");
        return KEEL_ERR_FAILED;
    }

    if (mysql_stmt_prepare(stmt, sql, (unsigned long) sql_len) != 0) {
        snprintf(
            tls_error,
            DB_ERROR_SIZE,
            "prepare 失败: (%u) %s | SQL: %.*s",
            (unsigned) mysql_stmt_errno(stmt),
            mysql_stmt_error(stmt),
            (int) (sql_len > 300 ? 300 : sql_len),
            sql
        );
        mysql_stmt_close(stmt);
        return KEEL_ERR_FAILED;
    }

    unsigned long expected = mysql_stmt_param_count(stmt);
    if (expected != (unsigned long) param_count) {
        snprintf(
            tls_error,
            DB_ERROR_SIZE,
            "参数个数不匹配：SQL 需要 %lu，实际 %u",
            expected,
            param_count
        );
        mysql_stmt_close(stmt);
        return KEEL_ERR_INVALID;
    }

    DbParams binds;
    if (!prepare_params(stmt, params, param_count, &binds)) {
        mysql_stmt_close(stmt);
        return KEEL_ERR_INVALID;
    }

    if (mysql_stmt_execute(stmt) != 0) {
        set_stmt_error(stmt, "执行失败");
        free_params(&binds, param_count);
        mysql_stmt_close(stmt);
        return KEEL_ERR_FAILED;
    }
    free_params(&binds, param_count);

    *out = stmt;
    return KEEL_OK;
}

static KeelStatus db_exec(KeelDbHandle *handle, const char *sql, uint32_t sql_len,
                          const KeelDbValue *params, uint32_t param_count,
                          uint64_t *affected) {
    DbHandleBase *base = base_of(handle);
    DbPool *pool = pool_of(base);
    if (pool == NULL || sql == NULL) return KEEL_ERR_INVALID;

    ensure_thread();

    MYSQL *mysql = NULL;
    int pooled = 0;
    if (base->kind == DB_KIND_POOL) {
        KeelStatus status = acquire_conn(pool, &mysql);
        if (status != KEEL_OK) return status;
        pooled = 1;
    } else {
        mysql = ((DbTx *) base)->mysql;
    }

    MYSQL_STMT *stmt = NULL;
    KeelStatus status = prepare_execute(mysql, sql, sql_len, params, param_count, &stmt);
    if (status != KEEL_OK) {
        if (pooled) release_conn(pool, mysql);
        return status;
    }

    tls_last_insert_id = (uint64_t) mysql_stmt_insert_id(stmt);
    if (affected != NULL) *affected = (uint64_t) mysql_stmt_affected_rows(stmt);
    mysql_stmt_close(stmt);
    if (pooled) release_conn(pool, mysql);
    return KEEL_OK;
}

static KeelDbType column_type(const MYSQL_FIELD *field) {
    switch (field->type) {
        case MYSQL_TYPE_TINY:
        case MYSQL_TYPE_SHORT:
        case MYSQL_TYPE_LONG:
        case MYSQL_TYPE_LONGLONG:
        case MYSQL_TYPE_INT24:
        case MYSQL_TYPE_YEAR:
            return KEEL_DB_INT;

        case MYSQL_TYPE_FLOAT:
        case MYSQL_TYPE_DOUBLE:
            return KEEL_DB_FLOAT;

        case MYSQL_TYPE_DECIMAL:
        case MYSQL_TYPE_NEWDECIMAL:
            return KEEL_DB_DECIMAL;

        case MYSQL_TYPE_DATE:
        case MYSQL_TYPE_DATETIME:
        case MYSQL_TYPE_TIMESTAMP:
            return KEEL_DB_TIMESTAMP;

        case MYSQL_TYPE_JSON:
            return KEEL_DB_JSON;

        case MYSQL_TYPE_TINY_BLOB:
        case MYSQL_TYPE_MEDIUM_BLOB:
        case MYSQL_TYPE_LONG_BLOB:
        case MYSQL_TYPE_BLOB:
        case MYSQL_TYPE_BIT:
        case MYSQL_TYPE_GEOMETRY:
        case MYSQL_TYPE_STRING:
        case MYSQL_TYPE_VAR_STRING:
            /* charsetnr 63 是 binary；TEXT 列也会被报成 BLOB，但字符集不是 63。 */
            return field->charsetnr == 63 ? KEEL_DB_BLOB : KEEL_DB_TEXT;

        default:
            return KEEL_DB_TEXT;
    }
}

static KeelStatus db_query(KeelDbHandle *handle, const char *sql, uint32_t sql_len,
                           const KeelDbValue *params, uint32_t param_count,
                           KeelDbResult **out) {
    DbHandleBase *base = base_of(handle);
    DbPool *pool = pool_of(base);
    if (pool == NULL || sql == NULL || out == NULL) return KEEL_ERR_INVALID;

    ensure_thread();

    MYSQL *mysql = NULL;
    int pooled = 0;
    if (base->kind == DB_KIND_POOL) {
        KeelStatus status = acquire_conn(pool, &mysql);
        if (status != KEEL_OK) return status;
        pooled = 1;
    } else {
        mysql = ((DbTx *) base)->mysql;
    }

    MYSQL_STMT *stmt = NULL;
    KeelStatus status = prepare_execute(mysql, sql, sql_len, params, param_count, &stmt);
    if (status != KEEL_OK) {
        if (pooled) release_conn(pool, mysql);
        return status;
    }

    MYSQL_RES *meta = mysql_stmt_result_metadata(stmt);
    if (meta == NULL) {
        set_stmt_error(stmt, "语句没有结果集");
        mysql_stmt_close(stmt);
        if (pooled) release_conn(pool, mysql);
        return KEEL_ERR_FAILED;
    }

    MYSQL_FIELD *fields = mysql_fetch_fields(meta);
    uint32_t columns = (uint32_t) mysql_num_fields(meta);
    if (columns > DB_MAX_COLUMNS) {
        set_error("结果列过多");
        mysql_free_result(meta);
        mysql_stmt_close(stmt);
        if (pooled) release_conn(pool, mysql);
        return KEEL_ERR_FAILED;
    }

    DbResult *result = (DbResult *) calloc(1, sizeof(DbResult));
    if (result == NULL) {
        set_error("结果集内存不足");
        mysql_free_result(meta);
        mysql_stmt_close(stmt);
        if (pooled) release_conn(pool, mysql);
        return KEEL_ERR_FAILED;
    }
    result->pool = pool;
    result->mysql = mysql;
    result->stmt = stmt;
    result->meta = meta;
    result->fields = fields;
    result->columns = columns;
    result->pooled = pooled;

    if (columns > 0) {
        result->binds = (MYSQL_BIND *) calloc(columns, sizeof(MYSQL_BIND));
        result->row = (KeelDbValue *) calloc(columns, sizeof(KeelDbValue));
        result->buffers = (uint8_t **) calloc(columns, sizeof(uint8_t *));
        result->capacities = (unsigned long *) calloc(columns, sizeof(unsigned long));
        result->lengths = (unsigned long *) calloc(columns, sizeof(unsigned long));
        result->is_null = (my_bool *) calloc(columns, sizeof(my_bool));
        result->truncated = (my_bool *) calloc(columns, sizeof(my_bool));
        result->ints = (int64_t *) calloc(columns, sizeof(int64_t));
        result->floats = (double *) calloc(columns, sizeof(double));
        if (result->binds == NULL || result->row == NULL || result->buffers == NULL ||
            result->capacities == NULL || result->lengths == NULL ||
            result->is_null == NULL || result->truncated == NULL ||
            result->ints == NULL || result->floats == NULL) {
            set_error("结果集内存不足");
            mysql_stmt_close(stmt);
            mysql_free_result(meta);
            free(result->binds);
            free(result->row);
            free(result->buffers);
            free(result->capacities);
            free(result->lengths);
            free(result->is_null);
            free(result->truncated);
            free(result->ints);
            free(result->floats);
            free(result);
            if (pooled) release_conn(pool, mysql);
            return KEEL_ERR_FAILED;
        }

        /*
         * 先把结果集收进客户端缓冲：`MYSQL_FIELD.max_length` 在 store 之后才准确，
         * 可以按列宽一次分配，避免 DOUBLE 等数值列被当成字符串截断。
         */
        if (mysql_stmt_store_result(stmt) != 0) {
            set_stmt_error(stmt, "缓存结果集失败");
            mysql_stmt_close(stmt);
            mysql_free_result(meta);
            free(result->binds);
            free(result->row);
            free(result->buffers);
            free(result->capacities);
            free(result->lengths);
            free(result->is_null);
            free(result->truncated);
            free(result->ints);
            free(result->floats);
            free(result);
            if (pooled) release_conn(pool, mysql);
            return KEEL_ERR_FAILED;
        }

        for (uint32_t index = 0; index < columns; index++) {
            unsigned long capacity = fields[index].max_length;
            if (capacity < 4096) capacity = 4096;
            if (capacity > DB_BUFFER_LIMIT) capacity = DB_BUFFER_LIMIT;
            result->buffers[index] = (uint8_t *) malloc(capacity);
            if (result->buffers[index] == NULL) {
                set_error("结果列内存不足");
                mysql_stmt_close(stmt);
                mysql_free_result(meta);
                for (uint32_t previous = 0; previous < index; previous++) {
                    free(result->buffers[previous]);
                }
                free(result->binds);
                free(result->row);
                free(result->buffers);
                free(result->capacities);
                free(result->lengths);
                free(result->is_null);
                free(result->truncated);
                free(result->ints);
                free(result->floats);
                free(result);
                if (pooled) release_conn(pool, mysql);
                return KEEL_ERR_FAILED;
            }
            result->capacities[index] = capacity;
            MYSQL_BIND *bind = &result->binds[index];
            bind->buffer_type = MYSQL_TYPE_STRING;
            bind->buffer = result->buffers[index];
            bind->buffer_length = capacity;
            bind->length = &result->lengths[index];
            bind->is_null = &result->is_null[index];
            bind->error = &result->truncated[index];
        }
        if (mysql_stmt_bind_result(stmt, result->binds) != 0) {
            set_stmt_error(stmt, "绑定结果失败");
            mysql_stmt_close(stmt);
            mysql_free_result(meta);
            for (uint32_t index = 0; index < columns; index++) {
                free(result->buffers[index]);
            }
            free(result->binds);
            free(result->row);
            free(result->buffers);
            free(result->capacities);
            free(result->lengths);
            free(result->is_null);
            free(result->truncated);
            free(result->ints);
            free(result->floats);
            free(result);
            if (pooled) release_conn(pool, mysql);
            return KEEL_ERR_FAILED;
        }
    }

    *out = (KeelDbResult *) result;
    return KEEL_OK;
}

static void clear_buffers(DbResult *result) {
    for (uint32_t index = 0; index < result->columns; index++) {
        free(result->buffers[index]);
        result->buffers[index] = NULL;
    }
}

static int parse_timestamp_text(const char *text, unsigned long length, int64_t *out) {
    if (text == NULL || length < 10) return 0;
    int year = 0;
    unsigned int month = 0, day = 0;
    unsigned int hour = 0, minute = 0, second = 0;
    unsigned long fraction = 0;
    int consumed = 0;

    if (sscanf(text, "%d-%u-%u%n", &year, &month, &day, &consumed) != 3) return 0;
    unsigned long position = (unsigned long) consumed;
    if (position < length && (text[position] == ' ' || text[position] == 'T')) position++;
    if (position + 8 <= length) {
        if (sscanf(text + position, "%u:%u:%u%n", &hour, &minute, &second, &consumed) != 3) {
            return 0;
        }
        position += (unsigned long) consumed;
    }
    if (position < length && text[position] == '.') {
        position++;
        unsigned long digits = 0;
        while (position < length && text[position] >= '0' && text[position] <= '9') {
            if (digits < 6) fraction = fraction * 10 + (unsigned long) (text[position] - '0');
            digits++;
            position++;
        }
        while (digits < 6) {
            fraction *= 10;
            digits++;
        }
    }

    if (month < 1 || month > 12 || day < 1 || day > 31) return 0;
    int64_t days = days_from_civil(year, month, day);
    int64_t seconds = days * 86400 + (int64_t) hour * 3600 + (int64_t) minute * 60 + second;
    *out = seconds * 1000000 + (int64_t) fraction;
    return 1;
}

static void fill_row(DbResult *result) {
    for (uint32_t index = 0; index < result->columns; index++) {
        KeelDbValue *value = &result->row[index];
        memset(value, 0, sizeof(*value));
        if (result->is_null[index]) {
            value->type = KEEL_DB_NULL;
            continue;
        }

        const uint8_t *bytes = result->buffers[index];
        unsigned long length = result->lengths[index];
        KeelDbType type = column_type(&result->fields[index]);

        switch (type) {
            case KEEL_DB_INT: {
                long long parsed = 0;
                if (bytes != NULL) {
                    char *end = NULL;
                    char *text = (char *) malloc(length + 1);
                    if (text == NULL) {
                        value->type = KEEL_DB_NULL;
                        break;
                    }
                    memcpy(text, bytes, length);
                    text[length] = '\0';
                    parsed = strtoll(text, &end, 10);
                    free(text);
                }
                result->ints[index] = (int64_t) parsed;
                value->type = KEEL_DB_INT;
                value->len = (uint32_t) sizeof(int64_t);
                value->bytes = (const uint8_t *) &result->ints[index];
                break;
            }

            case KEEL_DB_FLOAT: {
                double parsed = 0;
                if (bytes != NULL) {
                    char *end = NULL;
                    char *text = (char *) malloc(length + 1);
                    if (text == NULL) {
                        value->type = KEEL_DB_NULL;
                        break;
                    }
                    memcpy(text, bytes, length);
                    text[length] = '\0';
                    parsed = strtod(text, &end);
                    free(text);
                }
                result->floats[index] = parsed;
                value->type = KEEL_DB_FLOAT;
                value->len = (uint32_t) sizeof(double);
                value->bytes = (const uint8_t *) &result->floats[index];
                break;
            }

            case KEEL_DB_TIMESTAMP: {
                int64_t micros = 0;
                if (bytes != NULL) {
                    char *text = (char *) malloc(length + 1);
                    if (text == NULL) {
                        value->type = KEEL_DB_NULL;
                        break;
                    }
                    memcpy(text, bytes, length);
                    text[length] = '\0';
                    if (!parse_timestamp_text(text, length, &micros)) {
                        free(text);
                        value->type = KEEL_DB_TEXT;
                        value->len = (uint32_t) length;
                        value->bytes = bytes;
                        break;
                    }
                    free(text);
                }
                result->ints[index] = micros;
                value->type = KEEL_DB_TIMESTAMP;
                value->len = (uint32_t) sizeof(int64_t);
                value->bytes = (const uint8_t *) &result->ints[index];
                break;
            }

            case KEEL_DB_BLOB:
            case KEEL_DB_DECIMAL:
            case KEEL_DB_JSON:
            case KEEL_DB_TEXT:
            default:
                value->type = (uint32_t) type;
                value->len = (uint32_t) length;
                value->bytes = bytes;
                break;
        }
    }
}

static int32_t db_next(KeelDbResult *handle, const KeelDbValue **row, uint32_t *column_count) {
    DbResult *result = (DbResult *) handle;
    if (result == NULL || row == NULL || column_count == NULL) return -1;
    if (result->columns == 0) {
        *row = NULL;
        *column_count = 0;
        return 0;
    }

    for (uint32_t index = 0; index < result->columns; index++) {
        result->lengths[index] = 0;
        result->is_null[index] = 0;
        result->truncated[index] = 0;
    }

    int rc = mysql_stmt_fetch(result->stmt);
    if (rc == MYSQL_NO_DATA) return 0;
    if (rc != 0 && rc != MYSQL_DATA_TRUNCATED) {
        set_stmt_error(result->stmt, "获取结果行失败");
        return -1;
    }

    int rebind = 0;
    for (uint32_t index = 0; index < result->columns; index++) {
        if (result->is_null[index] || !result->truncated[index]) continue;

        unsigned long length = result->lengths[index];
        if (length > DB_BUFFER_LIMIT) {
            set_error("结果列超过 64MB 上限");
            return -1;
        }
        KeelDbType type = column_type(&result->fields[index]);
        if (type != KEEL_DB_TEXT && type != KEEL_DB_BLOB &&
            type != KEEL_DB_DECIMAL && type != KEEL_DB_JSON) {
            set_error("结果列超出缓冲区");
            return -1;
        }

        uint8_t *grown = (uint8_t *) realloc(result->buffers[index], length > 0 ? length : 1);
        if (grown == NULL) {
            set_error("结果列内存不足");
            return -1;
        }
        MYSQL_BIND one;
        memset(&one, 0, sizeof(one));
        one.buffer_type = MYSQL_TYPE_STRING;
        one.buffer = grown;
        one.buffer_length = length;
        one.length = &result->lengths[index];
        one.is_null = &result->is_null[index];
        one.error = &result->truncated[index];

        if (mysql_stmt_fetch_column(result->stmt, &one, index, 0) != 0) {
            set_error("读取结果列失败");
            return -1;
        }
        result->buffers[index] = grown;
        result->capacities[index] = length;
        result->binds[index].buffer = grown;
        result->binds[index].buffer_length = length;
        rebind = 1;
    }

    fill_row(result);
    *row = result->row;
    *column_count = result->columns;

    // 扩容过的列需要让下一次 fetch 使用新的缓冲区。
    if (rebind && mysql_stmt_bind_result(result->stmt, result->binds) != 0) {
        set_stmt_error(result->stmt, "重新绑定结果失败");
        return -1;
    }
    return 1;
}

static void db_result_free(KeelDbResult *handle) {
    DbResult *result = (DbResult *) handle;
    if (result == NULL) return;

    if (result->stmt != NULL) mysql_stmt_close(result->stmt);
    if (result->meta != NULL) mysql_free_result(result->meta);
    clear_buffers(result);
    free(result->binds);
    free(result->row);
    free(result->buffers);
    free(result->capacities);
    free(result->lengths);
    free(result->is_null);
    free(result->truncated);
    free(result->ints);
    free(result->floats);
    DbPool *pool = result->pool;
    MYSQL *mysql = result->mysql;
    int pooled = result->pooled;
    free(result);
    if (pooled && pool != NULL && mysql != NULL) release_conn(pool, mysql);
}

/* ------------------------------------------------------------------------- */
/* 事务                                                                       */
/* ------------------------------------------------------------------------- */

static KeelStatus db_begin(KeelDbHandle *handle, KeelDbHandle **tx) {
    DbHandleBase *base = base_of(handle);
    if (base == NULL || tx == NULL || base->kind != DB_KIND_POOL) return KEEL_ERR_INVALID;

    DbPool *pool = (DbPool *) base;
    ensure_thread();

    MYSQL *mysql = NULL;
    KeelStatus status = acquire_conn(pool, &mysql);
    if (status != KEEL_OK) return status;

    if (mysql_autocommit(mysql, 0) != 0) {
        set_mysql_error(mysql, "关闭自动提交失败");
        release_conn(pool, mysql);
        return KEEL_ERR_FAILED;
    }

    DbTx *created = (DbTx *) calloc(1, sizeof(DbTx));
    if (created == NULL) {
        mysql_autocommit(mysql, 1);
        release_conn(pool, mysql);
        set_error("事务句柄内存不足");
        return KEEL_ERR_FAILED;
    }
    created->base.kind = DB_KIND_TX;
    created->pool = pool;
    created->mysql = mysql;
    *tx = (KeelDbHandle *) created;
    return KEEL_OK;
}

static KeelStatus finish_transaction(KeelDbHandle *handle, int commit) {
    DbHandleBase *base = base_of(handle);
    if (base == NULL || base->kind != DB_KIND_TX) return KEEL_ERR_INVALID;

    DbTx *tx = (DbTx *) base;
    MYSQL *mysql = tx->mysql;
    KeelStatus status = KEEL_OK;

    if (commit) {
        if (mysql_commit(mysql) != 0) {
            set_mysql_error(mysql, "commit 失败");
            status = KEEL_ERR_FAILED;
        }
    } else {
        if (mysql_rollback(mysql) != 0) {
            set_mysql_error(mysql, "rollback 失败");
            status = KEEL_ERR_FAILED;
        }
    }

    mysql_autocommit(mysql, 1);
    release_conn(tx->pool, mysql);
    free(tx);
    return status;
}

static KeelStatus db_commit(KeelDbHandle *handle) {
    return finish_transaction(handle, 1);
}

static KeelStatus db_rollback(KeelDbHandle *handle) {
    return finish_transaction(handle, 0);
}

/* ------------------------------------------------------------------------- */
/* 其他 ABI                                                                   */
/* ------------------------------------------------------------------------- */

static KeelStatus db_last_insert_id(KeelDbHandle *handle, uint64_t *out) {
    if (base_of(handle) == NULL || out == NULL) return KEEL_ERR_INVALID;
    *out = tls_last_insert_id;
    return KEEL_OK;
}

static KeelStatus db_ping(KeelDbHandle *handle) {
    DbHandleBase *base = base_of(handle);
    DbPool *pool = pool_of(base);
    if (pool == NULL) return KEEL_ERR_INVALID;

    ensure_thread();

    if (base->kind == DB_KIND_TX) {
        MYSQL *mysql = ((DbTx *) base)->mysql;
        if (mysql_ping(mysql) != 0) {
            set_mysql_error(mysql, "ping 失败");
            return KEEL_ERR_FAILED;
        }
        return KEEL_OK;
    }

    MYSQL *mysql = NULL;
    KeelStatus status = acquire_conn(pool, &mysql);
    if (status != KEEL_OK) return status;
    if (mysql_ping(mysql) != 0) {
        set_mysql_error(mysql, "ping 失败");
        release_conn(pool, mysql);
        return KEEL_ERR_FAILED;
    }
    release_conn(pool, mysql);
    return KEEL_OK;
}

static KeelStatus db_last_error(KeelDbHandle *handle, char *buf, uint32_t capacity) {
    if (base_of(handle) == NULL || buf == NULL || capacity == 0) return KEEL_ERR_INVALID;
    if (tls_error[0] == '\0') return KEEL_ERR_INVALID;
    snprintf(buf, capacity, "%s", tls_error);
    return KEEL_OK;
}

static void db_close(void *instance) {
    DbPool *pool = (DbPool *) instance;
    if (pool == NULL) return;

    if (pool->conns != NULL) {
        for (uint32_t index = 0; index < pool->max_connections; index++) {
            DbConn *conn = pool->conns[index];
            if (conn == NULL) continue;
            if (conn->mysql != NULL) mysql_close(conn->mysql);
            free(conn);
        }
        free(pool->conns);
    }
    keel_mutex_destroy(&pool->lock);
    keel_cond_destroy(&pool->cond);
    free(pool->url);
    free(pool->url_username);
    free(pool->url_password);
    free(pool->username);
    free(pool->password);
    free(pool->host);
    free(pool->database);
    free(pool);
}

static KeelStatus db_open(const KeelHostApi *host, const void *config, uint32_t config_size,
                          void **instance, void **api) {
    (void) host;
    if (config == NULL || instance == NULL || api == NULL) return KEEL_ERR_INVALID;
    if (config_size != sizeof(KeelDbConfig)) return KEEL_ERR_UNSUPPORTED;

    const KeelDbConfig *settings = (const KeelDbConfig *) config;
    if (settings->url == NULL) return KEEL_ERR_INVALID;

    ensure_library();
    ensure_thread();

    DbPool *pool = (DbPool *) calloc(1, sizeof(DbPool));
    if (pool == NULL) {
        set_error("连接池内存不足");
        return KEEL_ERR_FAILED;
    }
    pool->base.kind = DB_KIND_POOL;
    keel_mutex_init(&pool->lock);
    keel_cond_init(&pool->cond);

    pool->max_connections = settings->max_connections > 0 ? settings->max_connections : 8;
    if (pool->max_connections > 64) pool->max_connections = 64;
    pool->connect_timeout_ms =
        settings->connect_timeout_ms > 0 ? settings->connect_timeout_ms : 3000;
    pool->url = copy_string(settings->url);
    if (pool->url == NULL || !parse_url(pool, settings->url)) {
        set_error("database.url 解析失败（期望 mysql://host:port/db）");
        db_close(pool);
        return KEEL_ERR_INVALID;
    }

    const char *username =
        settings->username != NULL ? settings->username : pool->url_username;
    const char *password =
        settings->password != NULL ? settings->password : pool->url_password;
    pool->username = copy_string(username != NULL ? username : "");
    pool->password = copy_string(password != NULL ? password : "");
    if (pool->username == NULL || pool->password == NULL) {
        set_error("连接配置内存不足");
        db_close(pool);
        return KEEL_ERR_FAILED;
    }

    pool->conns = (DbConn **) calloc(pool->max_connections, sizeof(DbConn *));
    if (pool->conns == NULL) {
        set_error("连接池内存不足");
        db_close(pool);
        return KEEL_ERR_FAILED;
    }

    pool->api.abi_version = KEEL_DB_ABI_VERSION;
    pool->api.struct_size = (uint32_t) sizeof(KeelDbApi);
    pool->api.exec = db_exec;
    pool->api.query = db_query;
    pool->api.next = db_next;
    pool->api.result_free = db_result_free;
    pool->api.begin = db_begin;
    pool->api.commit = db_commit;
    pool->api.rollback = db_rollback;
    pool->api.last_error = db_last_error;
    pool->api.last_insert_id = db_last_insert_id;
    pool->api.ping = db_ping;

    *instance = pool;
    *api = &pool->api;
    return KEEL_OK;
}

static const KeelCapabilityDescriptor CAPABILITY_DESCRIPTOR = {
    KEEL_ABI_VERSION,
    (uint32_t) sizeof(KeelCapabilityDescriptor),
    DB_ID,
    DB_PROVIDES,
    DB_VERSION,
    db_open,
    db_close,
};

KEEL_EXPORT const KeelCapabilityDescriptor *keel_capability_entry(void) {
    return &CAPABILITY_DESCRIPTOR;
}
