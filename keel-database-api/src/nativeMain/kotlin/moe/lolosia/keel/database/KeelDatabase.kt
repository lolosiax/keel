package moe.lolosia.keel.database

import kotlinx.cinterop.*
import moe.lolosia.keel.database.c.*

/**
 * 一个已打开的数据库连接池。
 *
 * 由 `currentDatabase()` 从 Host 下发的能力句柄构造；同一个实例可在插件的任意
 * Worker 线程使用，能力包内部负责连接池与互斥。
 */
class KeelDatabase internal constructor(
    private val api: CPointer<KeelDbApi>,
    private val handle: CPointer<KeelDbHandle>,
) {

    /**
     * 执行不返回结果集的语句。
     *
     * @return 受影响行数
     * @throws DatabaseException 能力包返回错误
     */
    fun exec(sql: String, params: List<DbValue> = emptyList()): Long = memScoped {
        val encoded = encodeParams(params)
        val affected = alloc<ULongVar>()
        val status = api.pointed.exec!!(
            handle,
            sql.cstr.ptr,
            sql.encodeToByteArray().size.toUInt(),
            encoded,
            params.size.toUInt(),
            affected.ptr,
        )
        if (status != STATUS_OK) throw failure(status.toInt(), "exec")
        affected.value.toLong()
    }

    /**
     * 查询并返回结果集；调用方负责 [QueryResult.close]。
     *
     * @throws DatabaseException 能力包返回错误
     */
    fun query(sql: String, params: List<DbValue> = emptyList()): QueryResult = memScoped {
        val encoded = encodeParams(params)
        val out = alloc<CPointerVar<KeelDbResult>>()
        val status = api.pointed.query!!(
            handle,
            sql.cstr.ptr,
            sql.encodeToByteArray().size.toUInt(),
            encoded,
            params.size.toUInt(),
            out.ptr,
        )
        if (status != STATUS_OK) throw failure(status.toInt(), "query")
        QueryResult(
            api = api,
            handle = out.value ?: throw DatabaseException("query 成功但没有返回结果集"),
            database = this@KeelDatabase,
        )
    }

    /**
     * 在事务里执行 [block]：正常返回提交，抛异常回滚。
     *
     * 事务句柄独占一条连接，传给 [block] 的实例只能在本次调用内使用。
     */
    fun <T> transaction(block: (KeelDatabase) -> T): T {
        val tx = memScoped {
            val out = alloc<CPointerVar<KeelDbHandle>>()
            val status = api.pointed.begin!!(handle, out.ptr)
            if (status != STATUS_OK) throw failure(status.toInt(), "begin")
            out.value ?: throw DatabaseException("begin 成功但没有返回事务句柄")
        }
        return try {
            val result = block(KeelDatabase(api, tx))
            val status = api.pointed.commit!!(tx)
            if (status != STATUS_OK) throw failure(status.toInt(), "commit")
            result
        } catch (e: Throwable) {
            api.pointed.rollback!!(tx)
            throw e
        }
    }

    /** 最近一次失败的文本；没有错误时为空串。 */
    val lastError: String get() = memScoped {
        val buffer = allocArray<ByteVar>(ERROR_BUFFER_SIZE)
        val status = api.pointed.last_error!!(handle, buffer, ERROR_BUFFER_SIZE.toUInt())
        if (status == STATUS_OK) buffer.toKString() else ""
    }

    /** 把 C 状态码与 `last_error` 合成异常；供 [QueryResult] 复用。 */
    internal fun failure(status: Int, action: String): DatabaseException {
        val detail = runCatching { lastError }.getOrDefault("")
        val text = if (detail.isBlank()) "status=$status" else "status=$status: $detail"
        return DatabaseException("$action 失败（$text）", status)
    }

    private fun MemScope.encodeParams(params: List<DbValue>): CPointer<KeelDbValue>? {
        if (params.isEmpty()) return null
        val array = allocArray<KeelDbValue>(params.size)
        params.forEachIndexed { index, value ->
            when (value) {
                DbValue.Null -> {
                    array[index].type = KEEL_DB_NULL
                    array[index].len = 0u
                    array[index].bytes = null
                }

                is DbValue.Text -> writeBytes(array, index, KEEL_DB_TEXT, value.value.encodeToByteArray())
                is DbValue.Integer -> writeBytes(array, index, KEEL_DB_INT, encodeLong(value.value))
                is DbValue.Decimal ->
                    writeBytes(array, index, KEEL_DB_FLOAT, encodeLong(value.value.toRawBits()))

                is DbValue.Bool ->
                    writeBytes(array, index, KEEL_DB_BOOL, byteArrayOf(if (value.value) 1 else 0))

                is DbValue.Blob -> writeBytes(array, index, KEEL_DB_BLOB, value.value)
            }
        }
        return array
    }

    private fun MemScope.writeBytes(
        array: CPointer<KeelDbValue>,
        index: Int,
        type: UInt,
        data: ByteArray,
    ) {
        val memory = if (data.isEmpty()) null else allocArray<UByteVar>(data.size).also { target ->
            data.forEachIndexed { offset, byte -> target[offset] = byte.toUByte() }
        }
        array[index].type = type
        array[index].len = data.size.toUInt()
        array[index].bytes = memory
    }

    private companion object {
        const val ERROR_BUFFER_SIZE = 512

        /**
         * 与 `keel_abi.h` 的 `KEEL_OK` 一致。
         *
         * cinterop 只为当前头文件里直接使用的枚举生成常量；这里经 keel_database.h
         * 间接引用 KeelStatus，所以不能直接用 `KEEL_OK`。
         */
        const val STATUS_OK = 0u

        fun encodeLong(value: Long): ByteArray {
            val data = ByteArray(8)
            for (index in 0 until 8) {
                data[index] = ((value shr (8 * index)) and 0xFF).toByte()
            }
            return data
        }
    }
}
