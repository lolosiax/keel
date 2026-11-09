package moe.lolosia.keel.database

import kotlinx.cinterop.*
import moe.lolosia.keel.database.c.*

/**
 * `KeelDbApi` 函数表的 expect 声明。
 *
 * KMP 的 `nativeMain` 元数据编译会把 commonized cinterop 结构体降级成不透明类型，
 * 直接写 `api.pointed.exec` 在 metadata 编译里会报「Unresolved reference: exec」。
 * 这里只声明调用签名，解引用放在 `src/nativeCinteropMain`（三个目标共享同一份 actual）。
 */
internal expect fun bridgeExec(
    api: CPointer<KeelDbApi>,
    handle: CPointer<KeelDbHandle>,
    sql: CPointer<ByteVar>,
    sqlLength: UInt,
    params: CPointer<KeelDbValue>?,
    paramCount: UInt,
    affected: CPointer<ULongVar>?,
): UInt

internal expect fun bridgeQuery(
    api: CPointer<KeelDbApi>,
    handle: CPointer<KeelDbHandle>,
    sql: CPointer<ByteVar>,
    sqlLength: UInt,
    params: CPointer<KeelDbValue>?,
    paramCount: UInt,
    out: CPointer<CPointerVar<KeelDbResult>>?,
): UInt

internal expect fun bridgeNext(
    api: CPointer<KeelDbApi>,
    result: CPointer<KeelDbResult>,
    row: CPointer<CPointerVar<KeelDbValue>>?,
    columnCount: CPointer<UIntVar>?,
): Int

internal expect fun bridgeResultFree(api: CPointer<KeelDbApi>, result: CPointer<KeelDbResult>)

internal expect fun bridgeBegin(
    api: CPointer<KeelDbApi>,
    pool: CPointer<KeelDbHandle>,
    tx: CPointer<CPointerVar<KeelDbHandle>>?,
): UInt

internal expect fun bridgeCommit(api: CPointer<KeelDbApi>, tx: CPointer<KeelDbHandle>): UInt

internal expect fun bridgeRollback(api: CPointer<KeelDbApi>, tx: CPointer<KeelDbHandle>): UInt

internal expect fun bridgeLastError(
    api: CPointer<KeelDbApi>,
    handle: CPointer<KeelDbHandle>,
    buffer: CPointer<ByteVar>,
    capacity: UInt,
): UInt
