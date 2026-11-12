package moe.lolosia.keel.host

import moe.lolosia.keel.api.log.*

/**
 * Host 日志环形缓冲区。
 *
 * 固定数组 + 头指针，追加与淘汰都是 O(1)；每条记录入队时只算一次成本，
 * 之后靠运行总量判断是否超限，从不遍历整个缓冲区。
 *
 * 实现只在 native 侧（需要跨线程互斥锁）；对外的读写接口放在 commonMain，
 * 让 [HostLogSink] 与 [HostLogSource] 共享同一份实现。
 */
internal expect object LogArchive {

    /**
     * 调整容量上限；缩小时立即按新上限淘汰。
     *
     * @param maxRecords 最多保留多少条；主保护，兜住对象头开销
     * @param maxMemoryBytes 估算内存上限（UTF-16 字符数 × 2）
     */
    fun configure(maxRecords: Int, maxMemoryBytes: Long)

    /** 追加一条日志，返回归档后的记录（含 id 与时间戳）。 */
    fun append(
        level: LogLevel,
        module: String,
        site: LogSite?,
        source: String?,
        moduleAnsi: String?,
        sourceAnsi: String?,
        message: String,
        error: LogError?,
        banner: Boolean = false,
    ): LogRecord

    /** 取 id 大于 [cursor] 的最多 [limit] 条；[cursor] 为 [Long.MAX_VALUE] 时返回空。 */
    fun page(cursor: Long, limit: Int): List<LogRecord>

    /** 取单独一条；已被淘汰或尚未分配时返回 null。 */
    fun get(id: Long): LogRecord?

    /** 当前最老 id；没有记录时等于 [nextId]。 */
    fun firstId(): Long

    /** 下一个待分配 id。 */
    fun nextId(): Long
}
