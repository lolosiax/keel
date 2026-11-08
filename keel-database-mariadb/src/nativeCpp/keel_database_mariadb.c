/*
 * MariaDB 数据库能力包（`kind: capability`，`provides: ["database"]`）。
 *
 * 本文件只实现 keel_database.h 的 ABI 与生命周期，具体 SQL 执行接在
 * `backend_*` 这几个函数上。当前构建未链接 libmariadb，除连通性自检
 * `select 1` 之外一律返回 KEEL_ERR_UNSUPPORTED，用于先打通
 * Host 加载 → open → 插件同步调用 → close 的完整链路；
 * 接入 libmariadb 时只替换 backend_*，Host 与插件侧不变。
 */

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "keel_abi.h"
#include "keel_database.h"

#define DB_ID "database.mariadb"
#define DB_PROVIDES "database"
#define DB_VERSION "0.1.0"

#define DB_KIND_POOL 1
#define DB_KIND_TX 2
#define DB_ERROR_SIZE 256

typedef struct DbHandleBase {
    int kind;
    char error[DB_ERROR_SIZE];
} DbHandleBase;

typedef struct DbPool {
    DbHandleBase base;
    KeelDbApi api;
    KeelDbConfig config;
    char *url;
    char *username;
    char *password;
} DbPool;

typedef struct DbTx {
    DbHandleBase base;
    DbPool *pool;
} DbTx;

typedef struct DbStubResult {
    int consumed;
    KeelDbValue row[1];
    uint8_t value[8];
} DbStubResult;

static void set_error(DbHandleBase *handle, const char *text) {
    if (handle == NULL) return;
    snprintf(handle->error, DB_ERROR_SIZE, "%s", text);
}

static void set_unsupported(DbHandleBase *handle) {
    set_error(handle, "database.mariadb: 当前构建未链接 libmariadb，只支持 select 1");
}

static char *copy_string(const char *value) {
    if (value == NULL) return NULL;
    size_t size = strlen(value) + 1;
    char *copy = (char *) malloc(size);
    if (copy != NULL) memcpy(copy, value, size);
    return copy;
}

static int equals_ignore_case(char a, char b) {
    if (a >= 'A' && a <= 'Z') a = (char) (a - 'A' + 'a');
    if (b >= 'A' && b <= 'Z') b = (char) (b - 'A' + 'a');
    return a == b;
}

/** 去掉首尾空白与结尾分号后，判断是否恰好是 `select 1`。 */
static int is_select_one(const char *sql, uint32_t len) {
    static const char expected[] = "select 1";
    while (len > 0 && (sql[0] == ' ' || sql[0] == '\t' || sql[0] == '\r' || sql[0] == '\n')) {
        sql++;
        len--;
    }
    while (len > 0) {
        char tail = sql[len - 1];
        if (tail == ' ' || tail == '\t' || tail == '\r' || tail == '\n' || tail == ';') {
            len--;
            continue;
        }
        break;
    }
    if (len != sizeof(expected) - 1) return 0;
    for (uint32_t index = 0; index < len; index++) {
        if (!equals_ignore_case(sql[index], expected[index])) return 0;
    }
    return 1;
}

/** 取 handle 的公共部分；NULL 或未知类型返回 NULL。 */
static DbHandleBase *base_of(KeelDbHandle *handle) {
    if (handle == NULL) return NULL;
    DbHandleBase *base = (DbHandleBase *) handle;
    if (base->kind != DB_KIND_POOL && base->kind != DB_KIND_TX) return NULL;
    return base;
}

/* ------------------------------------------------------------------------- */
/* 后端边界：接入 libmariadb 时替换这一组函数                               */
/* ------------------------------------------------------------------------- */

static void backend_init(DbPool *pool) {
    pool->api.abi_version = KEEL_DB_ABI_VERSION;
    pool->api.struct_size = (uint32_t) sizeof(KeelDbApi);
}

static KeelStatus backend_exec(DbHandleBase *base, const char *sql, uint32_t sql_len,
                               const KeelDbValue *params, uint32_t param_count,
                               uint64_t *affected) {
    (void) base;
    (void) sql;
    (void) sql_len;
    (void) params;
    (void) param_count;
    if (affected != NULL) *affected = 0;
    set_unsupported(base);
    return KEEL_ERR_UNSUPPORTED;
}

static KeelStatus backend_query(DbHandleBase *base, const char *sql, uint32_t sql_len,
                                const KeelDbValue *params, uint32_t param_count,
                                KeelDbResult **out) {
    (void) params;
    (void) param_count;
    if (out == NULL) return KEEL_ERR_INVALID;

    if (!is_select_one(sql, sql_len)) {
        set_unsupported(base);
        return KEEL_ERR_UNSUPPORTED;
    }

    DbStubResult *result = (DbStubResult *) calloc(1, sizeof(DbStubResult));
    if (result == NULL) {
        set_error(base, "database.mariadb: 结果集内存不足");
        return KEEL_ERR_FAILED;
    }
    for (int index = 0; index < 8; index++) {
        result->value[index] = (uint8_t) (index == 0 ? 1 : 0);
    }
    result->row[0].type = KEEL_DB_INT;
    result->row[0].len = 8;
    result->row[0].bytes = result->value;
    *out = (KeelDbResult *) result;
    return KEEL_OK;
}

/* ------------------------------------------------------------------------- */
/* ABI 函数                                                                  */
/* ------------------------------------------------------------------------- */

static KeelStatus db_exec(KeelDbHandle *handle, const char *sql, uint32_t sql_len,
                          const KeelDbValue *params, uint32_t param_count,
                          uint64_t *affected) {
    DbHandleBase *base = base_of(handle);
    if (base == NULL || sql == NULL) return KEEL_ERR_INVALID;
    return backend_exec(base, sql, sql_len, params, param_count, affected);
}

static KeelStatus db_query(KeelDbHandle *handle, const char *sql, uint32_t sql_len,
                           const KeelDbValue *params, uint32_t param_count,
                           KeelDbResult **out) {
    DbHandleBase *base = base_of(handle);
    if (base == NULL || sql == NULL || out == NULL) return KEEL_ERR_INVALID;
    return backend_query(base, sql, sql_len, params, param_count, out);
}

static int32_t db_next(KeelDbResult *result, const KeelDbValue **row, uint32_t *column_count) {
    DbStubResult *stub = (DbStubResult *) result;
    if (stub == NULL || row == NULL || column_count == NULL) return -1;
    if (stub->consumed) return 0;
    stub->consumed = 1;
    *row = stub->row;
    *column_count = 1;
    return 1;
}

static void db_result_free(KeelDbResult *result) {
    free(result);
}

static KeelStatus db_begin(KeelDbHandle *handle, KeelDbHandle **tx) {
    DbHandleBase *base = base_of(handle);
    if (base == NULL || tx == NULL) return KEEL_ERR_INVALID;

    DbTx *created = (DbTx *) calloc(1, sizeof(DbTx));
    if (created == NULL) {
        set_error(base, "database.mariadb: 事务句柄内存不足");
        return KEEL_ERR_FAILED;
    }
    created->base.kind = DB_KIND_TX;
    created->pool = (DbPool *) (base->kind == DB_KIND_POOL ? base : NULL);
    *tx = (KeelDbHandle *) created;
    return KEEL_OK;
}

static KeelStatus db_commit(KeelDbHandle *handle) {
    DbHandleBase *base = base_of(handle);
    if (base == NULL || base->kind != DB_KIND_TX) return KEEL_ERR_INVALID;
    free(handle);
    return KEEL_OK;
}

static KeelStatus db_rollback(KeelDbHandle *handle) {
    return db_commit(handle);
}

static KeelStatus db_last_error(KeelDbHandle *handle, char *buf, uint32_t capacity) {
    DbHandleBase *base = base_of(handle);
    if (base == NULL || buf == NULL || capacity == 0) return KEEL_ERR_INVALID;
    if (base->error[0] == '\0') return KEEL_ERR_INVALID;
    snprintf(buf, capacity, "%s", base->error);
    return KEEL_OK;
}

static void db_close(void *instance) {
    DbPool *pool = (DbPool *) instance;
    if (pool == NULL) return;
    free(pool->url);
    free(pool->username);
    free(pool->password);
    free(pool);
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
    pool->url = copy_string(settings->url);
    pool->username = copy_string(settings->username);
    pool->password = copy_string(settings->password);
    if (pool->url == NULL) {
        db_close(pool);
        return KEEL_ERR_FAILED;
    }

    pool->config.struct_size = (uint32_t) sizeof(KeelDbConfig);
    pool->config.url = pool->url;
    pool->config.username = pool->username;
    pool->config.password = pool->password;
    pool->config.max_connections = settings->max_connections;
    pool->config.connect_timeout_ms = settings->connect_timeout_ms;
    backend_init(pool);

    pool->api.exec = db_exec;
    pool->api.query = db_query;
    pool->api.next = db_next;
    pool->api.result_free = db_result_free;
    pool->api.begin = db_begin;
    pool->api.commit = db_commit;
    pool->api.rollback = db_rollback;
    pool->api.last_error = db_last_error;

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
