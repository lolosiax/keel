package moe.lolosia.keel.database

import kotlinx.cinterop.*
import moe.lolosia.keel.database.c.*

/**
 * 查询结果集。
 *
 * 必须调用 [close] 释放能力包里的资源；推荐用 `use { }`。未读完就关闭是允许的。
 */
class QueryResult internal constructor(
    private val api: CPointer<KeelDbApi>,
    private val handle: CPointer<KeelDbResult>,
    private val database: KeelDatabase,
) : AutoCloseable {

    /** 当前行的列数；第一次 [next] 成功后才有意义。 */
    var columns: Int = 0
        private set

    /** 当前行；[next] 返回 false 后为 null。 */
    var row: Row? = null
        private set

    private var closed = false

    /**
     * 前进一行。
     *
     * @return false 表示没有更多行
     * @throws DatabaseException 能力包返回错误
     */
    fun next(): Boolean = memScoped {
        check(!closed) { "结果集已关闭" }
        val out = alloc<CPointerVar<KeelDbValue>>()
        val count = alloc<UIntVar>()
        val status = api.pointed.next!!(handle, out.ptr, count.ptr)
        if (status < 0) {
            columns = 0
            row = null
            throw database.failure(status, "next")
        }
        if (status == 0) {
            columns = 0
            row = null
            return@memScoped false
        }

        val total = count.value.toInt()
        val base = out.value ?: throw DatabaseException("结果行指针为空")
        val values = ArrayList<DbValue>(total)
        for (index in 0 until total) {
            values += base[index].toDbValue()
        }
        columns = total
        row = Row(values)
        true
    }

    override fun close() {
        if (closed) return
        closed = true
        api.pointed.result_free!!(handle)
    }
}

private fun KeelDbValue.toDbValue(): DbValue = when (type) {
    KEEL_DB_NULL -> DbValue.Null
    KEEL_DB_TEXT -> DbValue.Text(readText())
    KEEL_DB_INT -> DbValue.Integer(readLong())
    KEEL_DB_FLOAT -> DbValue.Decimal(Double.fromBits(readLong()))
    KEEL_DB_BOOL -> DbValue.Bool(readBytes(1)?.firstOrNull() != 0.toByte())
    KEEL_DB_BLOB -> DbValue.Blob(readBytes(len.toInt()) ?: ByteArray(0))
    else -> throw DatabaseException("未知的结果列类型：$type")
}

private fun KeelDbValue.readBytes(count: Int): ByteArray? {
    if (count <= 0) return ByteArray(0)
    val ptr = bytes ?: return null
    return ptr.readBytes(count)
}

private fun KeelDbValue.readText(): String =
    readBytes(len.toInt())?.decodeToString() ?: ""

private fun KeelDbValue.readLong(): Long {
    val data = readBytes(8) ?: return 0L
    var value = 0L
    for (index in 0 until 8) {
        value = value or ((data[index].toLong() and 0xFF) shl (8 * index))
    }
    return value
}
