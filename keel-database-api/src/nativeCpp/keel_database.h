#ifndef KEEL_DATABASE_H
#define KEEL_DATABASE_H

/*
 * 数据库能力包 ABI。
 *
 * 谁遵守这份头文件：
 * - 能力包（`keel-database-mariadb` 等，纯 C 动态库）实现 [KeelDbApi]；
 * - Host 的能力加载器按 `database.url` 的 scheme 选中能力包，调用 `open` 并把函数表
 *   与实例填进 `KeelHostApi.database` / `database_handle`；
 * - 业务插件通过 `keel-database-api` 的 Kotlin 门面调用，不直接拼结构体。
 *
 * 规则：
 * 1. 只允许 blittable 类型；结构体首字段是 `abi_version + struct_size`，只追加字段。
 * 2. 调用发生在插件 Worker 线程上，**不进入任何 Kotlin 运行时**；能力包必须线程安全。
 * 3. 调用方拥有参数，只在本函数调用期间有效；结果集与错误串由能力包拥有，按函数说明释放。
 * 4. 未连接 / 连不上数据库时，查询返回 `KEEL_ERR_FAILED`，用 `last_error` 取原因；
 *    业务侧映射为 503，不阻断 Host 启动。
 * 5. v2 只追加字段与方法：旧字段偏移不变，版本由 `abi_version` 严格校验。
 */

#include <stdint.h>

#include "keel_abi.h"

#define KEEL_DB_ABI_VERSION 2u

/* 值的类型；参数与结果行共用。 */
typedef enum KeelDbType {
    KEEL_DB_NULL = 0,
    KEEL_DB_TEXT = 1,
    KEEL_DB_INT = 2,
    KEEL_DB_FLOAT = 3,
    KEEL_DB_BOOL = 4,
    KEEL_DB_BLOB = 5,
    KEEL_DB_DECIMAL = 6,    /* 规范十进制文本（UTF-8），不经过 double */
    KEEL_DB_TIMESTAMP = 7,  /* 8 字节小端 int64，epoch 微秒（UTC） */
    KEEL_DB_JSON = 8,       /* UTF-8 JSON 文本 */
    KEEL_DB_UUID = 9        /* 36 字符文本 */
} KeelDbType;

/*
 * 一个值。
 *
 * bytes 的编码按 type 区分：
 * - TEXT / BLOB / DECIMAL / JSON / UUID：原始字节，不算结尾 '\0'；
 * - INT / TIMESTAMP：8 字节小端二进制补码（TIMESTAMP 是 epoch 微秒）；
 * - FLOAT：8 字节小端 IEEE-754；
 * - BOOL：1 字节，0 为假；
 * - NULL：bytes 与 len 都被忽略。
 */
typedef struct KeelDbValue {
    uint32_t type;          /* KeelDbType */
    uint32_t len;
    const uint8_t *bytes;
} KeelDbValue;

/* 驱动私有配置项；例如 `busy_timeout` / `sslmode` / `charset`。 */
typedef struct KeelDbOption {
    const char *key;
    const char *value;
} KeelDbOption;

/*
 * `open` 的配置；字段由 Host 从 `database:` 配置段填好。
 *
 * 字符串与 [options] 数组只在 `open` 调用期间有效，能力包需要时自行复制。
 */
typedef struct KeelDbConfig {
    uint32_t struct_size;   /* sizeof(KeelDbConfig) */
    const char *url;        /* mysql://host:port/db */
    const char *username;
    const char *password;
    uint32_t max_connections;
    uint32_t connect_timeout_ms;
    uint32_t option_count;          /* [options] 数组长度，可为 0 */
    const KeelDbOption *options;
} KeelDbConfig;

/*
 * 不透明句柄。
 *
 * 同一个类型既是连接池，也是事务：能力包自己区分。
 * cinterop 侧给完整占位定义，原因与 keel_abi.h 的 KeelQueueTag 相同。
 */
#ifdef KEEL_CINTEROP
typedef struct KeelDbHandleTag {
    void *reserved;
} KeelDbHandle;
#else
typedef struct KeelDbHandleTag KeelDbHandle;
#endif

/* 不透明结果集；由 query 创建、result_free 释放。 */
#ifdef KEEL_CINTEROP
typedef struct KeelDbResultTag {
    void *reserved;
} KeelDbResult;
#else
typedef struct KeelDbResultTag KeelDbResult;
#endif

/*
 * 能力函数表。
 *
 * 所有函数可以在任意线程调用；能力包内部负责连接池、事务表与互斥。
 */
typedef struct KeelDbApi {
    uint32_t abi_version;   /* KEEL_DB_ABI_VERSION */
    uint32_t struct_size;   /* sizeof(KeelDbApi) */

    /*
     * 执行不返回结果集的语句。
     *
     * @param handle 池或事务
     * @param params 调用方拥有，仅本次调用有效
     * @param affected 受影响行数，可为 NULL
     */
    KeelStatus (*exec)(KeelDbHandle *handle, const char *sql, uint32_t sql_len,
                       const KeelDbValue *params, uint32_t param_count,
                       uint64_t *affected);

    /*
     * 执行查询。
     *
     * 成功时 *out 由能力包分配，调用方必须用 result_free 释放。
     */
    KeelStatus (*query)(KeelDbHandle *handle, const char *sql, uint32_t sql_len,
                        const KeelDbValue *params, uint32_t param_count,
                        KeelDbResult **out);

    /*
     * 取下一条结果行。
     *
     * @param row 指向行首的 KeelDbValue 数组，**在下一次 next 或 result_free 前有效**
     * @return 1 有数据 / 0 结束 / 负值错误（用 last_error 取原因）
     */
    int32_t (*next)(KeelDbResult *result, const KeelDbValue **row, uint32_t *column_count);

    /* 释放结果集；对 NULL 调用无副作用。 */
    void (*result_free)(KeelDbResult *result);

    /* 开始事务：从池里取一条独占连接，返回事务句柄（由 commit / rollback 释放）。 */
    KeelStatus (*begin)(KeelDbHandle *pool, KeelDbHandle **tx);

    KeelStatus (*commit)(KeelDbHandle *tx);

    KeelStatus (*rollback)(KeelDbHandle *tx);

    /*
     * 复制最近一次错误的文本（UTF-8，自动补 '\0'）。
     *
     * @return KEEL_OK 表示写入了非空文本；KEEL_ERR_INVALID 表示没有错误或缓冲太小
     */
    KeelStatus (*last_error)(KeelDbHandle *handle, char *buf, uint32_t capacity);

    /*
     * 最近一次写入生成的自增主键。
     *
     * 以**调用线程**的最近一次 exec 为准：连接池下不能用
     * `select last_insert_id()` 回查，那条语句可能落到另一条连接上。
     * 没有写入过时返回 KEEL_OK 与 0。
     */
    KeelStatus (*last_insert_id)(KeelDbHandle *handle, uint64_t *out);

    /*
     * 连通性检查。
     *
     * 池句柄会借出一条连接执行 ping；事务句柄只检查当前连接。
     * 失败时用 last_error 取原因。
     */
    KeelStatus (*ping)(KeelDbHandle *handle);
} KeelDbApi;

#endif /* KEEL_DATABASE_H */
