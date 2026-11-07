package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.*
import moe.lolosia.keel.abi.c.*

/** 与插件 inbox 同量级：覆盖启动、关闭与少量回投事件。 */
private const val HOST_INBOX_CAPACITY = 256u

private val hostInbox: CPointer<KeelQueue> =
    keel_queue_create(HOST_INBOX_CAPACITY) ?: error("创建 Host 收件箱失败")

/** plugin_id → 插件 inbox；启动成功后登记，关闭前移除。 */
private val pluginInboxes = mutableMapOf<ULong, CPointer<KeelQueue>>()

internal fun registerPluginInbox(pluginId: ULong, inbox: CPointer<KeelQueue>) {
    pluginInboxes[pluginId] = inbox
}

internal fun unregisterPluginInbox(pluginId: ULong) {
    pluginInboxes.remove(pluginId)
}

internal fun pluginInbox(pluginId: ULong): CPointer<KeelQueue>? = pluginInboxes[pluginId]

/**
 * 向已登记的插件投递一条无载荷事件。
 *
 * @return false 表示目标不在线，或队列已满 / 已关闭
 */
internal fun pushToPlugin(
    pluginId: ULong,
    kind: Int,
    subject: ULong,
    correlation: ULong,
    status: Int = KEEL_OK.toInt(),
): Boolean {
    val inbox = pluginInbox(pluginId) ?: return false
    return memScoped {
        val event = alloc<KeelEvent>()
        event.size = sizeOf<KeelEvent>().toUInt()
        event.kind = kind.toUShort()
        event.source = 0uL
        event.target = pluginId
        event.correlation = correlation
        event.subject = subject
        event.status = status
        keel_queue_push(inbox, event.ptr, 0u) == KEEL_OK
    }
}

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
    // 普通指针，插件在自己的 Worker 线程上只读，不进入 Host 运行时
    api.host_inbox = hostInbox
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
    val payload: ByteArray? = null,
)

/**
 * 把原始事件复制成标量快照。
 *
 * 载荷会被复制；返回后原事件可以立即 release。
 */
internal fun snapshotOf(event: CPointer<KeelEvent>): HostEvent {
    val e = event.pointed
    val payload = if (e.payload_size > 0u) {
        e.payload?.reinterpret<ByteVar>()?.readBytes(e.payload_size.toInt())
    } else {
        null
    }
    return HostEvent(
        kind = e.kind.toInt(),
        source = e.source,
        target = e.target,
        correlation = e.correlation,
        subject = e.subject,
        status = e.status,
        payload = payload,
    )
}

/**
 * 从 Host 收件箱取一条事件，最多等 [timeoutMs] 毫秒。
 *
 * 载荷在返回前复制并归还，调用方拿到的是纯 Kotlin 快照。
 *
 * @return null 表示超时或队列已关闭
 */
internal fun popHostEvent(timeoutMs: UInt): HostEvent? = memScoped {
    val event = alloc<KeelEvent>()
    if (keel_queue_pop(hostInbox, event.ptr, timeoutMs) != KEEL_OK) return@memScoped null

    // 先复制标量与载荷；载荷按契约立即归还，之后指针不再有效
    val snapshot = snapshotOf(event.ptr)
    event.release?.invoke(event.release_user, event.ptr)
    snapshot
}

/**
 * 取一条原始事件交给 [handle] 处理，由它决定载荷归属。
 *
 * [handle] 返回 true 表示载荷所有权已转移（例如原样转发给目标插件 inbox）；
 * 返回 false 表示事件已被消费或丢弃，这里负责调用 release 归还载荷。
 *
 * @return false 表示收件箱为空或已关闭
 */
internal fun popHostEventRaw(timeoutMs: UInt, handle: (CPointer<KeelEvent>) -> Boolean): Boolean =
    memScoped {
        val event = alloc<KeelEvent>()
        if (keel_queue_pop(hostInbox, event.ptr, timeoutMs) != KEEL_OK) return@memScoped false
        if (!handle(event.ptr)) {
            event.release?.invoke(event.release_user, event.ptr)
        }
        true
    }
