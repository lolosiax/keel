package moe.lolosia.keel.database

import kotlinx.cinterop.*
import moe.lolosia.keel.database.c.*

/** [DbBridge] 的 actual：三个目标共用这一份，解引用 cinterop 生成的函数指针表。 */

internal actual fun bridgeExec(
    api: CPointer<KeelDbApi>,
    handle: CPointer<KeelDbHandle>,
    sql: CPointer<ByteVar>,
    sqlLength: UInt,
    params: CPointer<KeelDbValue>?,
    paramCount: UInt,
    affected: CPointer<ULongVar>?,
): UInt = api.pointed.exec!!(handle, sql, sqlLength, params, paramCount, affected)

internal actual fun bridgeQuery(
    api: CPointer<KeelDbApi>,
    handle: CPointer<KeelDbHandle>,
    sql: CPointer<ByteVar>,
    sqlLength: UInt,
    params: CPointer<KeelDbValue>?,
    paramCount: UInt,
    out: CPointer<CPointerVar<KeelDbResult>>?,
): UInt = api.pointed.query!!(handle, sql, sqlLength, params, paramCount, out)

internal actual fun bridgeNext(
    api: CPointer<KeelDbApi>,
    result: CPointer<KeelDbResult>,
    row: CPointer<CPointerVar<KeelDbValue>>?,
    columnCount: CPointer<UIntVar>?,
): Int = api.pointed.next!!(result, row, columnCount)

internal actual fun bridgeResultFree(api: CPointer<KeelDbApi>, result: CPointer<KeelDbResult>) {
    api.pointed.result_free!!(result)
}

internal actual fun bridgeBegin(
    api: CPointer<KeelDbApi>,
    pool: CPointer<KeelDbHandle>,
    tx: CPointer<CPointerVar<KeelDbHandle>>?,
): UInt = api.pointed.begin!!(pool, tx)

internal actual fun bridgeCommit(api: CPointer<KeelDbApi>, tx: CPointer<KeelDbHandle>): UInt =
    api.pointed.commit!!(tx)

internal actual fun bridgeRollback(api: CPointer<KeelDbApi>, tx: CPointer<KeelDbHandle>): UInt =
    api.pointed.rollback!!(tx)

internal actual fun bridgeLastError(
    api: CPointer<KeelDbApi>,
    handle: CPointer<KeelDbHandle>,
    buffer: CPointer<ByteVar>,
    capacity: UInt,
): UInt = api.pointed.last_error!!(handle, buffer, capacity)
