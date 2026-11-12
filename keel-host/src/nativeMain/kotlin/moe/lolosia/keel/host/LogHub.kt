package moe.lolosia.keel.host

import kotlinx.coroutines.channels.Channel
import moe.lolosia.keel.abi.c.KEEL_EVENT_COMMAND
import moe.lolosia.keel.abi.c.KEEL_SUBJECT_LOG_WAKE
import moe.lolosia.keel.api.host.LogSubscribeReply
import moe.lolosia.keel.host.plugin.pushToPlugin

/**
 * 日志订阅表与合并唤醒。
 *
 * 日志正文不进事件泵：写入方只置一个标记，泵线程每轮为每个订阅投**最多一条**
 * 无载荷 `LOG_WAKE`，插件收到后按游标拉取。订阅表只在泵线程读写。
 */
internal object LogHub {

    private class Sub(val id: Long, val pluginId: ULong, val local: Channel<Unit>?)

    private var nextId = 1L

    private val subs = LinkedHashMap<Long, Sub>()

    /** 每轮最多唤醒多少个订阅，避免日志风暴时泵线程只顾投唤醒。 */
    private const val WAKE_BUDGET = 8

    fun subscribe(pluginId: ULong, startId: Long): LogSubscribeReply {
        // -1 表示「只要新日志」：游标停在当前最新 id 上
        val cursor = if (startId < 0) LogArchive.nextId() - 1 else startId
        val id = nextId++
        subs[id] = Sub(id, pluginId, null)
        return LogSubscribeReply(id, cursor, LogArchive.firstId(), LogArchive.nextId())
    }

    fun unsubscribe(subscriptionId: Long) {
        subs.remove(subscriptionId)
    }

    /** 插件停用 / 卸载：丢掉它的全部订阅。 */
    fun dropPlugin(pluginId: ULong) {
        subs.entries.removeAll { it.value.pluginId == pluginId }
    }

    /** Host 本地订阅（控制台 / 测试）：用协程通道代替跨运行时唤醒。 */
    fun registerLocal(channel: Channel<Unit>): Long {
        val id = nextId++
        subs[id] = Sub(id, 0uL, channel)
        return id
    }

    fun unregisterLocal(subscriptionId: Long) {
        subs.remove(subscriptionId)
    }

    /** 订阅是否归属于该插件；0 表示一次性拉取，不要求归属。 */
    fun owns(pluginId: ULong, subscriptionId: Long): Boolean {
        if (subscriptionId == 0L) return true
        return subs[subscriptionId]?.pluginId == pluginId
    }

    /** 泵线程每轮调用：合并的「有新日志」转成每个订阅一条唤醒。 */
    fun flushWakes() {
        if (!LogWake.consume()) return

        var sent = 0
        val iterator = subs.values.iterator()
        while (iterator.hasNext() && sent < WAKE_BUDGET) {
            val sub = iterator.next()
            if (sub.local != null) {
                sub.local.trySend(Unit)
                sent++
                continue
            }
            val pushed = pushToPlugin(
                pluginId = sub.pluginId,
                kind = KEEL_EVENT_COMMAND.toInt(),
                subject = KEEL_SUBJECT_LOG_WAKE.toULong(),
                correlation = sub.id.toULong(),
            )
            // 插件离线：不再为它保留订阅
            if (pushed) sent++ else iterator.remove()
        }
        // 这轮没轮到的订阅，下一轮继续
        if (sent >= WAKE_BUDGET) LogWake.mark()
    }
}
