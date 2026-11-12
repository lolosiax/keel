package moe.lolosia.keel.host

import kotlinx.cinterop.CPointer
import moe.lolosia.keel.abi.c.*
import moe.lolosia.keel.api.log.*

/**
 * 日志环形缓冲区的 native 实现。
 *
 * 锁用 ABI 自带的 [keel_lock_create]：日志会从 Ktor 线程与事件泵线程同时写入，
 * 不能假设单线程。锁内只做数组搬运与长度计算，不调用其它 Kotlin 代码。
 */
internal actual object LogArchive {

    private const val DEFAULT_MAX_RECORDS = 10_000

    /** 默认 30 MiB；与 `application.yaml` 的 `log.archive.maxMemoryBytes` 对应。 */
    private const val DEFAULT_MAX_MEMORY_BYTES = 30L * 1024 * 1024

    /** 单条记录的固定开销：对象头、id / 时间戳等标量。 */
    private const val RECORD_OVERHEAD = 96L

    /** 错误链每层的粗略开销。 */
    private const val CHAIN_ENTRY_OVERHEAD = 64L

    private val lock: CPointer<KeelLock> = keel_lock_create() ?: error("创建日志缓冲锁失败")

    private var slots = arrayOfNulls<LogRecord>(DEFAULT_MAX_RECORDS)
    private var head = 0
    private var count = 0
    private var nextSequence = 1L
    private var totalBytes = 0L
    private var maxRecords = DEFAULT_MAX_RECORDS
    private var maxMemoryBytes = DEFAULT_MAX_MEMORY_BYTES

    actual fun configure(maxRecords: Int, maxMemoryBytes: Long) {
        require(maxRecords > 0) { "maxRecords 必须大于 0" }
        require(maxMemoryBytes > 0) { "maxMemoryBytes 必须大于 0" }
        locked {
            if (maxRecords == this.maxRecords && maxMemoryBytes == this.maxMemoryBytes) return@locked
            val live = snapshot()
            this.maxRecords = maxRecords
            this.maxMemoryBytes = maxMemoryBytes
            slots = arrayOfNulls(maxRecords)
            head = 0
            count = 0
            totalBytes = 0
            live.takeLast(maxRecords).forEach { push(it) }
            trim()
        }
    }

    actual fun append(
        level: LogLevel,
        module: String,
        site: LogSite?,
        source: String?,
        moduleAnsi: String?,
        sourceAnsi: String?,
        message: String,
        error: LogError?,
        banner: Boolean,
    ): LogRecord = locked {
        val record = LogRecord(
            id = nextSequence++,
            timeMillis = currentTimeMillis(),
            time = formatLocalTimestamp(),
            level = level,
            module = module,
            site = site,
            source = source,
            message = message,
            error = error,
            banner = banner,
            moduleAnsi = moduleAnsi,
            sourceAnsi = sourceAnsi,
        )
        push(record)
        trim()
        record
    }

    actual fun page(cursor: Long, limit: Int): List<LogRecord> = locked {
        if (limit <= 0 || cursor == Long.MAX_VALUE) return@locked emptyList()
        val oldest = nextSequence - count
        val from = maxOf(cursor + 1, oldest)
        if (from >= nextSequence) return@locked emptyList()
        val size = minOf((nextSequence - from).toInt(), limit)
        List(size) { offset ->
            val index = (head + (from - oldest) + offset) % maxRecords
            slots[index.toInt()]!!
        }
    }

    actual fun get(id: Long): LogRecord? = locked {
        val oldest = nextSequence - count
        if (id < oldest || id >= nextSequence) return@locked null
        val index = (head + (id - oldest)) % maxRecords
        slots[index.toInt()]
    }

    actual fun firstId(): Long = locked { nextSequence - count }

    actual fun nextId(): Long = locked { nextSequence }

    private fun push(record: LogRecord) {
        if (count == maxRecords) evict()
        slots[(head + count) % maxRecords] = record
        count++
        totalBytes += costOf(record)
    }

    /** 单条记录也可能超过上限；至少保留一条，避免缓冲区永远是空的。 */
    private fun trim() {
        while (count > 1 && totalBytes > maxMemoryBytes) evict()
    }

    private fun evict() {
        if (count == 0) return
        val record = slots[head]
        slots[head] = null
        head = (head + 1) % maxRecords
        count--
        if (record != null) totalBytes -= costOf(record)
    }

    private fun snapshot(): List<LogRecord> = List(count) { offset ->
        slots[(head + offset) % maxRecords]!!
    }

    /**
     * 估算一条记录占用的内存。
     *
     * `String.length` 是 O(1)，K/N 的 String 是 UTF-16，1 char ≈ 2 字节，
     * 中文不需要额外转码；错误链只按层数计，不逐帧扫描。
     */
    private fun costOf(record: LogRecord): Long {
        val text = record.time.length + record.module.length +
            (record.source?.length ?: 0) + record.message.length +
            (record.error?.text?.length ?: 0) + siteLength(record.site)
        return RECORD_OVERHEAD + 8L + 2L * text +
            (record.error?.chain?.size ?: 0) * CHAIN_ENTRY_OVERHEAD
    }

    private fun siteLength(site: LogSite?): Int =
        if (site == null) 0 else {
            site.displayName.length + site.file.length +
                (site.packageName?.length ?: 0) + (site.className?.length ?: 0) +
                (site.functionName?.length ?: 0)
        }

    private inline fun <T> locked(block: () -> T): T {
        keel_lock_acquire(lock)
        try {
            return block()
        } finally {
            keel_lock_release(lock)
        }
    }
}
