package moe.lolosia.keel.host.plugin

import moe.lolosia.keel.abi.c.*

/**
 * Host 侧事件泵。
 *
 * 长驻 Host 应持续消费收件箱；CLI 在退出前用 [drain] 清空积压的插件日志。
 */
internal object HostPump {

    /** 非阻塞地取空当前收件箱，目前只处理插件日志。 */
    fun drain() {
        while (true) {
            val event = popHostEvent(0u) ?: break
            if (event.kind == KEEL_EVENT_LOG.toInt()) handleLogEvent(event)
        }
    }
}
