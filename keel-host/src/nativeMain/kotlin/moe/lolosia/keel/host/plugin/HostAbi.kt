package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.*
import moe.lolosia.keel.abi.c.*

/** 与插件 inbox 同量级：覆盖启动、关闭与少量回投事件。 */
private const val HOST_INBOX_CAPACITY = 256u

private val hostInbox: CPointer<KeelQueue> =
    keel_queue_create(HOST_INBOX_CAPACITY) ?: error("创建 Host 收件箱失败")

private fun hostInboxRef(): CPointer<KeelQueue>? = hostInbox

/**
 * Host 侧 ABI 端点：一份 `KeelHostApi` + 一个进程内收件箱。
 *
 * 每个二进制各带一份 `keel_queue_*` 实现，但队列结构布局一致，插件可以往这个
 * 队列里 push；队列只按值搬运 `KeelEvent`，不接管载荷所有权。
 */
internal val hostAbi: CPointer<KeelHostApi> = nativeHeap.alloc<KeelHostApi>().also { api ->
    api.abi_version = KEEL_ABI_VERSION
    api.struct_size = sizeOf<KeelHostApi>().toUInt()
    api.host_id = 0uL
    api.host_inbox = staticCFunction(::hostInboxRef)
    // 句柄表未接入：handle_* 恒为 null，插件不能申请跨运行时句柄
    api.handle_new = null
    api.handle_retain = null
    api.handle_release = null
}.ptr

/**
 * 回投事件的标量快照。
 *
 * 载荷在 [popHostEvent] 里就归还了，所以这里不保留指针，避免悬空读取。
 */
internal class HostEvent(
    val kind: Int,
    val source: ULong,
    val target: ULong,
    val correlation: ULong,
    val subject: ULong,
    val status: Int,
)

/**
 * 从 Host 收件箱取一条事件，最多等 [timeoutMs] 毫秒。
 *
 * @return null 表示超时或队列已关闭
 */
internal fun popHostEvent(timeoutMs: UInt): HostEvent? = memScoped {
    val event = alloc<KeelEvent>()
    when (keel_queue_pop(hostInbox, event.ptr, timeoutMs)) {
        KEEL_OK -> {
            // 先复制标量；载荷按契约立即归还，之后指针不再有效
            val snapshot = HostEvent(
                kind = event.kind.toInt(),
                source = event.source,
                target = event.target,
                correlation = event.correlation,
                subject = event.subject,
                status = event.status,
            )
            event.release?.invoke(event.release_user, event.ptr)
            snapshot
        }

        else -> null
    }
}
