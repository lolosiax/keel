/*
 * SQLite 数据库能力包（`kind: capability`，`provides: ["database"]`）。
 *
 * SQLite amalgamation 以静态库并进本动态库，部署时只有 `lib/<target>/` 下的一个文件。
 * 连接模型是单连接 + 互斥：SQLite 本身单写，查询结果在 result_free 之前独占连接；
 * 事务句柄由 begin 取锁，commit / rollback 释放，v1 不支持嵌套事务。
 *
 * 实现 keel_database.h 的 ABI v2：exec / query / next / result_free / begin /
 * commit / rollback / last_error / last_insert_id / ping。
 */

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "keel_abi.h"
#include "keel_database.h"
#include "keel_platform.h"
#include "sqlite3.h"

#define DB_ID "database.sqlite"
#define DB_PROVIDES "database"
#define DB_VERSION "0.1.0"

#define DB_KIND_POOL 1
#define DB_KIND_TX 2
#define DB_ERROR_SIZE 256

typedef struct DbHandleBase {
    int kind;
} DbHandleBase;

typedef struct DbPool {
    DbHandleBase base;
    KeelDbApi api;
    sqlite3 *db;
    keel_mutex_t lock;
    char error[DB_ERROR_SIZE];
    char *path;
    int in_transaction;
} DbPool;

typedef struct DbTx {
    DbHandleBase base;
    DbPool *pool;
} DbTx;

typedef struct DbSqliteResult {
    sqlite3_stmt *stmt;
    DbPool *pool;
    /** 池句柄查询时 result_free 负责解锁；事务句柄查询时锁已由 begin 持有。 */
    int unlock_on_free;
    uint32_t columns;
    KeelDbValue *row;
    int64_t *ints;
    double *floats;
} DbSqliteResult;

static void set_error(DbPool *pool, const char *text) {
    if (pool == NULL) return;
    snprintf(pool->error, DB_ERROR_SIZE, "%s", text);
}

static void set_sqlite_error(DbPool *pool, const char *prefix) {
    if (pool == NULL) return;
    snprintf(pool->error, DB_ERROR_SIZE, "%s: %s", prefix, sqlite3_errmsg(pool->db));
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

static int bind_params(sqlite3_stmt *stmt, const KeelDbValue *params, uint32_t count) {
    for (uint32_t index = 0; index < count; index++) {
        const KeelDbValue *value = &params[index];
        int position = (int) (index + 1);
        switch (value->type) {
            case KEEL_DB_NULL:
                sqlite3_bind_null(stmt, position);
                break;
            case KEEL_DB_INT: {
                int64_t number = 0;
                if (value->bytes != NULL && value->len >= sizeof(number)) {
                    memcpy(&number, value->bytes, sizeof(number));
                }
                sqlite3_bind_int64(stmt, position, (sqlite3_int64) number);
                break;
            }
            case KEEL_DB_FLOAT: {
                double number = 0;
                if (value->bytes != NULL && value->len >= sizeof(number)) {
                    memcpy(&number, value->bytes, sizeof(number));
                }
                sqlite3_bind_double(stmt, position, number);
                break;
            }
            case KEEL_DB_BOOL:
                sqlite3_bind_int(stmt, position, (value->bytes != NULL && value->bytes[0] != 0) ? 1 : 0);
                break;
            case KEEL_DB_TEXT:
                sqlite3_bind_text(
                    stmt,
                    position,
                    (const char *) value->bytes,
                    (int) value->len,
                    SQLITE_TRANSIENT
                );
                break;
            case KEEL_DB_BLOB:
                sqlite3_bind_blob(
                    stmt,
                    position,
                    value->bytes,
                    (int) value->len,
                    SQLITE_TRANSIENT
                );
                break;
            default:
                return 0;
        }
    }
    return 1;
}

/* 执行不返回结果集的语句；调用方已持有连接锁。 */
static KeelStatus exec_locked(DbPool *pool, const char *sql, uint32_t sql_len,
                              const KeelDbValue *params, uint32_t param_count,
                              uint64_t *affected) {
    sqlite3_stmt *stmt = NULL;
    const char *tail = NULL;
    int rc = sqlite3_prepare_v2(pool->db, sql, (int) sql_len, &stmt, &tail);
    if (rc != SQLITE_OK) {
        set_sqlite_error(pool, "prepare 失败");
        return KEEL_ERR_FAILED;
    }
    if (stmt == NULL) {
        set_error(pool, "SQL 为空");
        return KEEL_ERR_INVALID;
    }
    if (!bind_params(stmt, params, param_count)) {
        sqlite3_finalize(stmt);
        set_error(pool, "参数类型不受支持");
        return KEEL_ERR_INVALID;
    }
    while ((rc = sqlite3_step(stmt)) == SQLITE_ROW) {
        /* 带 RETURNING 的语句也会先有行，跳过即可 */
    }
    if (rc != SQLITE_DONE) {
        set_sqlite_error(pool, "exec 失败");
        sqlite3_finalize(stmt);
        return KEEL_ERR_FAILED;
    }
    sqlite3_finalize(stmt);
    if (affected != NULL) *affected = (uint64_t) sqlite3_changes(pool->db);
    return KEEL_OK;
}

static KeelStatus db_exec(KeelDbHandle *handle, const char *sql, uint32_t sql_len,
                          const KeelDbValue *params, uint32_t param_count,
                          uint64_t *affected) {
    DbHandleBase *base = base_of(handle);
    DbPool *pool = pool_of(base);
    if (pool == NULL || sql == NULL) return KEEL_ERR_INVALID;

    int lock = base->kind == DB_KIND_POOL;
    if (lock) keel_mutex_lock(&pool->lock);
    KeelStatus status = exec_locked(pool, sql, sql_len, params, param_count, affected);
    if (lock) keel_mutex_unlock(&pool->lock);
    return status;
}

static KeelStatus db_query(KeelDbHandle *handle, const char *sql, uint32_t sql_len,
                           const KeelDbValue *params, uint32_t param_count,
                           KeelDbResult **out) {
    DbHandleBase *base = base_of(handle);
    DbPool *pool = pool_of(base);
    if (pool == NULL || sql == NULL || out == NULL) return KEEL_ERR_INVALID;

    int lock = base->kind == DB_KIND_POOL;
    if (lock) keel_mutex_lock(&pool->lock);

    sqlite3_stmt *stmt = NULL;
    const char *tail = NULL;
    int rc = sqlite3_prepare_v2(pool->db, sql, (int) sql_len, &stmt, &tail);
    if (rc != SQLITE_OK || stmt == NULL) {
        if (rc == SQLITE_OK) set_error(pool, "SQL 为空");
        else set_sqlite_error(pool, "prepare 失败");
        if (stmt != NULL) sqlite3_finalize(stmt);
        if (lock) keel_mutex_unlock(&pool->lock);
        return KEEL_ERR_FAILED;
    }
    if (!bind_params(stmt, params, param_count)) {
        sqlite3_finalize(stmt);
        if (lock) keel_mutex_unlock(&pool->lock);
        set_error(pool, "参数类型不受支持");
        return KEEL_ERR_INVALID;
    }

    int columns = sqlite3_column_count(stmt);
    DbSqliteResult *result = (DbSqliteResult *) calloc(1, sizeof(DbSqliteResult));
    if (result == NULL) {
        sqlite3_finalize(stmt);
        if (lock) keel_mutex_unlock(&pool->lock);
        set_error(pool, "结果集内存不足");
        return KEEL_ERR_FAILED;
    }
    result->stmt = stmt;
    result->pool = pool;
    result->unlock_on_free = lock;
    result->columns = (uint32_t) columns;
    if (columns > 0) {
        result->row = (KeelDbValue *) calloc((size_t) columns, sizeof(KeelDbValue));
        result->ints = (int64_t *) calloc((size_t) columns, sizeof(int64_t));
        result->floats = (double *) calloc((size_t) columns, sizeof(double));
        if (result->row == NULL || result->ints == NULL || result->floats == NULL) {
            sqlite3_finalize(stmt);
            free(result->row);
            free(result->ints);
            free(result->floats);
            free(result);
            if (lock) keel_mutex_unlock(&pool->lock);
            set_error(pool, "结果集内存不足");
            return KEEL_ERR_FAILED;
        }
    }
    *out = (KeelDbResult *) result;
    return KEEL_OK;
}

static void fill_row(DbSqliteResult *result) {
    sqlite3_stmt *stmt = result->stmt;
    for (uint32_t index = 0; index < result->columns; index++) {
        KeelDbValue *value = &result->row[index];
        int column = (int) index;
        switch (sqlite3_column_type(stmt, column)) {
            case SQLITE_NULL:
                value->type = KEEL_DB_NULL;
                value->len = 0;
                value->bytes = NULL;
                break;
            case SQLITE_INTEGER:
                result->ints[index] = (int64_t) sqlite3_column_int64(stmt, column);
                value->type = KEEL_DB_INT;
                value->len = (uint32_t) sizeof(int64_t);
                value->bytes = (const uint8_t *) &result->ints[index];
                break;
            case SQLITE_FLOAT:
                result->floats[index] = (double) sqlite3_column_double(stmt, column);
                value->type = KEEL_DB_FLOAT;
                value->len = (uint32_t) sizeof(double);
                value->bytes = (const uint8_t *) &result->floats[index];
                break;
            case SQLITE_TEXT:
                value->type = KEEL_DB_TEXT;
                value->len = (uint32_t) sqlite3_column_bytes(stmt, column);
                value->bytes = (const uint8_t *) sqlite3_column_text(stmt, column);
                break;
            case SQLITE_BLOB:
            default:
                value->type = KEEL_DB_BLOB;
                value->len = (uint32_t) sqlite3_column_bytes(stmt, column);
                value->bytes = (const uint8_t *) sqlite3_column_blob(stmt, column);
                break;
        }
    }
}

static int32_t db_next(KeelDbResult *handle, const KeelDbValue **row, uint32_t *column_count) {
    DbSqliteResult *result = (DbSqliteResult *) handle;
    if (result == NULL || row == NULL || column_count == NULL) return -1;
    int rc = sqlite3_step(result->stmt);
    if (rc == SQLITE_ROW) {
        fill_row(result);
        *row = result->row;
        *column_count = result->columns;
        return 1;
    }
    if (rc == SQLITE_DONE) return 0;
    set_sqlite_error(result->pool, "next 失败");
    return -1;
}

static void db_result_free(KeelDbResult *handle) {
    DbSqliteResult *result = (DbSqliteResult *) handle;
    if (result == NULL) return;
    DbPool *pool = result->pool;
    if (result->stmt != NULL) sqlite3_finalize(result->stmt);
    free(result->row);
    free(result->ints);
    free(result->floats);
    int unlock = result->unlock_on_free;
    free(result);
    if (unlock) keel_mutex_unlock(&pool->lock);
}

static KeelStatus db_begin(KeelDbHandle *handle, KeelDbHandle **tx) {
    DbHandleBase *base = base_of(handle);
    if (base == NULL || tx == NULL) return KEEL_ERR_INVALID;
    if (base->kind != DB_KIND_POOL) return KEEL_ERR_INVALID;

    DbPool *pool = (DbPool *) base;
    if (pool->in_transaction) {
        set_error(pool, "不支持嵌套事务");
        return KEEL_ERR_INVALID;
    }

    keel_mutex_lock(&pool->lock);
    KeelStatus status = exec_locked(pool, "BEGIN IMMEDIATE", 15, NULL, 0, NULL);
    if (status != KEEL_OK) {
        keel_mutex_unlock(&pool->lock);
        return status;
    }

    DbTx *created = (DbTx *) calloc(1, sizeof(DbTx));
    if (created == NULL) {
        exec_locked(pool, "ROLLBACK", 8, NULL, 0, NULL);
        keel_mutex_unlock(&pool->lock);
        set_error(pool, "事务句柄内存不足");
        return KEEL_ERR_FAILED;
    }
    created->base.kind = DB_KIND_TX;
    created->pool = pool;
    pool->in_transaction = 1;
    *tx = (KeelDbHandle *) created;
    return KEEL_OK;
}

static KeelStatus finish_transaction(KeelDbHandle *handle, const char *sql, uint32_t sql_len) {
    DbHandleBase *base = base_of(handle);
    if (base == NULL || base->kind != DB_KIND_TX) return KEEL_ERR_INVALID;
    DbTx *tx = (DbTx *) base;
    DbPool *pool = tx->pool;

    KeelStatus status = exec_locked(pool, sql, sql_len, NULL, 0, NULL);
    pool->in_transaction = 0;
    free(tx);
    keel_mutex_unlock(&pool->lock);
    return status;
}

static KeelStatus db_commit(KeelDbHandle *handle) {
    return finish_transaction(handle, "COMMIT", 6);
}

static KeelStatus db_rollback(KeelDbHandle *handle) {
    return finish_transaction(handle, "ROLLBACK", 8);
}

static KeelStatus db_last_insert_id(KeelDbHandle *handle, uint64_t *out) {
    DbHandleBase *base = base_of(handle);
    DbPool *pool = pool_of(base);
    if (pool == NULL || out == NULL) return KEEL_ERR_INVALID;

    int lock = base->kind == DB_KIND_POOL;
    if (lock) keel_mutex_lock(&pool->lock);
    *out = (uint64_t) sqlite3_last_insert_rowid(pool->db);
    if (lock) keel_mutex_unlock(&pool->lock);
    return KEEL_OK;
}

static KeelStatus db_ping(KeelDbHandle *handle) {
    DbHandleBase *base = base_of(handle);
    DbPool *pool = pool_of(base);
    if (pool == NULL) return KEEL_ERR_INVALID;

    int lock = base->kind == DB_KIND_POOL;
    if (lock) keel_mutex_lock(&pool->lock);
    KeelStatus status = exec_locked(pool, "select 1", 8, NULL, 0, NULL);
    if (lock) keel_mutex_unlock(&pool->lock);
    return status;
}

static KeelStatus db_last_error(KeelDbHandle *handle, char *buf, uint32_t capacity) {
    DbHandleBase *base = base_of(handle);
    DbPool *pool = pool_of(base);
    if (pool == NULL || buf == NULL || capacity == 0) return KEEL_ERR_INVALID;
    if (pool->error[0] == '\0') return KEEL_ERR_INVALID;
    snprintf(buf, capacity, "%s", pool->error);
    return KEEL_OK;
}

static void db_close(void *instance) {
    DbPool *pool = (DbPool *) instance;
    if (pool == NULL) return;
    if (pool->db != NULL) sqlite3_close_v2(pool->db);
    keel_mutex_destroy(&pool->lock);
    free(pool->path);
    free(pool);
}

/** `sqlite:///abs/path`、`sqlite://rel/path`；Windows 上 `/C:/...` 去掉前导斜杠。 */
static char *database_path(const char *url) {
    const char *path = url;
    if (strncmp(path, "sqlite://", 9) == 0) path += 9;
    else if (strncmp(path, "sqlite:", 7) == 0) path += 7;

#ifdef _WIN32
    if (path[0] == '/' && path[1] != '\0' && path[2] == ':') path++;
#endif

    size_t size = strlen(path) + 1;
    char *copy = (char *) malloc(size);
    if (copy != NULL) memcpy(copy, path, size);
    return copy;
}

static KeelStatus db_open(const KeelHostApi *host, const void *config, uint32_t config_size,
                          void **instance, void **api) {
    (void) host;
    if (config == NULL || instance == NULL || api == NULL) return KEEL_ERR_INVALID;
    if (config_size != sizeof(KeelDbConfig)) return KEEL_ERR_UNSUPPORTED;

    const KeelDbConfig *settings = (const KeelDbConfig *) config;
    if (settings->url == NULL) return KEEL_ERR_INVALID;

    DbPool *pool = (DbPool *) calloc(1, sizeof(DbPool));
    if (pool == NULL) return KEEL_ERR_FAILED;

    pool->base.kind = DB_KIND_POOL;
    keel_mutex_init(&pool->lock);
    pool->path = database_path(settings->url);
    if (pool->path == NULL) {
        db_close(pool);
        return KEEL_ERR_FAILED;
    }

    int flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX;
    int rc = sqlite3_open_v2(pool->path, &pool->db, flags, NULL);
    if (rc != SQLITE_OK) {
        set_sqlite_error(pool, "打开数据库失败");
        db_close(pool);
        return KEEL_ERR_FAILED;
    }

    int timeout = settings->connect_timeout_ms > 0 ? (int) settings->connect_timeout_ms : 3000;
    sqlite3_busy_timeout(pool->db, timeout);
    exec_locked(pool, "PRAGMA journal_mode=WAL", 23, NULL, 0, NULL);
    exec_locked(pool, "PRAGMA foreign_keys=ON", 22, NULL, 0, NULL);

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
