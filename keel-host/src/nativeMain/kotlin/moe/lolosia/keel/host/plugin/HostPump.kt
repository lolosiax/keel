package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.pointed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import moe.lolosia.keel.abi.c.*
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.web.RouteDescriptor
import moe.lolosia.keel.host.route.HostRoutes

/** 一次 Host → 插件的在途请求；[reply] 由泵线程完成。 */
internal class HostRequest(
    val pluginId: ULong,
    val subject: ULong,
    val payload: ByteArray?,
    val timeoutMs: Long,
) {
    val reply = CompletableDeferred<HostEvent?>()
    var correlation: ULong = 0uL
    var deadlineMs: Long = 0L
}

/**
 * Host 侧事件泵。
 *
 * 长驻 Host 应持续消费收件箱；CLI 在退出前用 [drain] 清空积压的插件日志。
 * 日志就地消费；`target` 非 0 的事件按 plugin_id 转发给目标插件 inbox。
 *
 * 请求 / 应答：外部线程（Ktor）经 [request] 把请求交给这里的队列并挂起，
 * 泵线程持有 pending 表，收到同 correlation 的 COMPLETE 后 resume。
 * pending 表因此只被泵线程访问，不需要锁。
 */
internal object HostPump {

    /** 同一种「无法处理」只告警一次，避免事件风暴时刷屏。 */
    private val warned = mutableSetOf<String>()

    private val clock = TimeSource.Monotonic.markNow()

    /** 外部线程投进来的请求；只由泵线程取出。 */
    private val incoming = Channel<HostRequest>(Channel.UNLIMITED)

    /** correlation → 在途请求；只在泵线程读写。 */
    private val pending = mutableMapOf<ULong, HostRequest>()

    /** 非阻塞地取空当前收件箱。 */
    fun drain() {
        dispatchIncoming()
        expireRequests()
        while (popHostEventRaw(0u) { event -> dispatch(event) }) {
            // 返回 false 表示收件箱已空
        }
        expireRequests()
    }

    /** 阻塞等待并处理一条事件；长驻 Host 的主循环每轮调用一次。 */
    fun serve(timeoutMs: UInt = 200u) {
        dispatchIncoming()
        expireRequests()
        popHostEventRaw(timeoutMs) { event -> dispatch(event) }
        expireRequests()
    }

    /**
     * 从任意线程发起一次请求。
     *
     * 挂起当前协程，泵线程收到同 correlation 的 COMPLETE 后完成 [HostRequest.reply]。
     * `ApplicationCall` / `Continuation` 不跨 ABI，跨界只有 correlation 与载荷。
     *
     * @return 超时或插件不在线时返回 null
     */
    suspend fun request(
        pluginId: ULong,
        subject: ULong,
        payload: ByteArray? = null,
        timeoutMs: Long = 2000,
    ): HostEvent? {
        val request = HostRequest(pluginId, subject, payload, timeoutMs)
        incoming.send(request)
        // 泵线程可能正阻塞在 200ms 的 pop 里；投一条 WAKE 让它立刻处理新请求
        wakeHostPump()
        return withTimeoutOrNull(timeoutMs) { request.reply.await() }
    }

    /**
     * 处理一条原始事件。
     *
     * @return true 表示载荷所有权已转移给目标插件（由它 release）；false 由调用方归还
     */
    fun dispatch(event: CPointer<KeelEvent>): Boolean {
        val e = event.pointed
        val kind = e.kind.toInt()

        // Host 内部唤醒：立刻把外部线程提交的请求转给插件，不再等 pop 超时
        if (kind == KEEL_EVENT_COMMAND.toInt() &&
            e.target == 0uL &&
            e.subject == KEEL_SUBJECT_WAKE.toULong()
        ) {
            dispatchIncoming()
            return false
        }

        if (kind == KEEL_EVENT_COMPLETE.toInt()) {
            val request = pending.remove(e.correlation)
            if (request != null) {
                request.reply.complete(snapshotOf(event))
                return false
            }
        }

        if (kind == KEEL_EVENT_LOG.toInt()) {
            handleLogEvent(snapshotOf(event))
            return false
        }

        if (kind == KEEL_EVENT_COMMAND.toInt() &&
            e.target == 0uL &&
            e.subject == KEEL_SUBJECT_ROUTE_REGISTER.toULong()
        ) {
            handleSnapshot(snapshotOf(event))
            return false
        }

        val target = e.target
        if (target == 0uL) {
            warnOnce("kind=$kind", "Host 没有该事件的处理器")
            return false
        }

        val inbox = pluginInbox(target)
        if (inbox == null) {
            warnOnce("kind=$kind->$target", "目标插件不在线")
            return false
        }
        return keel_queue_push(inbox, event, 0u) == KEEL_OK
    }

    private fun warnOnce(key: String, reason: String) {
        if (!warned.add(key)) return
        Log.warn("丢弃事件 $key：$reason", tag = "EventBus")
    }

    /**
     * 处理不依赖原始指针的事件（启动等待期、阻塞请求期间使用）。
     *
     * 目前只认路由注册；返回 true 表示事件已被消费。
     */
    fun handleSnapshot(event: HostEvent): Boolean {
        if (event.kind != KEEL_EVENT_COMMAND.toInt() ||
            event.target != 0uL ||
            event.subject != KEEL_SUBJECT_ROUTE_REGISTER.toULong()
        ) {
            return false
        }

        val payload = event.payload ?: return true
        try {
            val descriptors = Json.decodeFromString<List<RouteDescriptor>>(payload.decodeToString())
            HostRoutes.register(event.source, descriptors)
        } catch (err: Throwable) {
            Log.warn("路由注册失败：${err.message}", tag = "Router")
        }
        return true
    }

    private fun dispatchIncoming() {
        while (true) {
            val request = incoming.tryReceive().getOrNull() ?: break
            start(request)
        }
    }

    private fun start(request: HostRequest) {
        val correlation = nextCorrelation()
        request.correlation = correlation
        request.deadlineMs = nowMs() + request.timeoutMs

        val pushed = pushToPlugin(
            pluginId = request.pluginId,
            kind = KEEL_EVENT_COMMAND.toInt(),
            subject = request.subject,
            correlation = correlation,
            payload = request.payload,
        )
        if (!pushed) {
            request.reply.complete(null)
            return
        }
        pending[correlation] = request
    }

    /** 清理超时请求；回包晚于 deadline 时也会被丢弃。 */
    private fun expireRequests() {
        if (pending.isEmpty()) return
        val now = nowMs()
        val expired = pending.filterValues { it.deadlineMs <= now }.keys.toList()
        expired.forEach { correlation -> pending.remove(correlation)?.reply?.complete(null) }
    }

    private fun nowMs(): Long = clock.elapsedNow().inWholeMilliseconds

    /**
     * Host 向插件发起一次同步请求：投 COMMAND，等同 correlation 的 COMPLETE。
     *
     * 只在启动阶段（单线程、还没进入 serve 循环）使用；运行期请用 [request]。
     *
     * @return true 表示插件回了 `KEEL_OK`
     */
    fun requestPlugin(pluginId: ULong, subject: ULong, timeoutMs: UInt = 2000u): Boolean {
        val correlation = nextCorrelation()
        if (!pushToPlugin(pluginId, KEEL_EVENT_COMMAND.toInt(), subject, correlation)) {
            return false
        }

        val mark = TimeSource.Monotonic.markNow()
        val timeout = timeoutMs.toLong().milliseconds
        while (true) {
            val remaining = timeout - mark.elapsedNow()
            if (remaining <= 0.milliseconds) return false

            val event = popHostEvent(remaining.inWholeMilliseconds.toUInt()) ?: return false
            when {
                event.kind == KEEL_EVENT_LOG.toInt() -> handleLogEvent(event)
                event.kind == KEEL_EVENT_COMPLETE.toInt() && event.correlation == correlation ->
                    return event.status == KEEL_OK.toInt()

                handleSnapshot(event) -> Unit
                else -> warnOnce("kind=${event.kind}->${event.target}", "请求期间收到无关事件")
            }
        }
    }
}
