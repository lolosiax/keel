package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.pointed
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import moe.lolosia.keel.abi.c.*
import moe.lolosia.keel.api.Log

/**
 * Host 侧事件泵。
 *
 * 长驻 Host 应持续消费收件箱；CLI 在退出前用 [drain] 清空积压的插件日志。
 * 日志就地消费；`target` 非 0 的事件按 plugin_id 转发给目标插件 inbox。
 */
internal object HostPump {

    /** 同一种「无法处理」只告警一次，避免事件风暴时刷屏。 */
    private val warned = mutableSetOf<String>()

    /** 非阻塞地取空当前收件箱。 */
    fun drain() {
        while (popHostEventRaw(0u) { event -> dispatch(event) }) {
            // 返回 false 表示收件箱已空
        }
    }

    /** 阻塞等待并处理一条事件；长驻 Host 的主循环每轮调用一次。 */
    fun serve(timeoutMs: UInt = 200u) {
        popHostEventRaw(timeoutMs) { event -> dispatch(event) }
    }

    /**
     * 处理一条原始事件。
     *
     * @return true 表示载荷所有权已转移给目标插件（由它 release）；false 由调用方归还
     */
    fun dispatch(event: CPointer<KeelEvent>): Boolean {
        val e = event.pointed
        val kind = e.kind.toInt()

        if (kind == KEEL_EVENT_LOG.toInt()) {
            handleLogEvent(snapshotOf(event))
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
     * Host 向插件发起一次请求：投 COMMAND，等同 correlation 的 COMPLETE。
     *
     * 等待期间顺带消费日志事件；其它事件告警一次后丢弃。
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

                else -> warnOnce("kind=${event.kind}->${event.target}", "请求期间收到无关事件")
            }
        }
    }
}
