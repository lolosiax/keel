package moe.lolosia.keel.api.worker

import kotlin.time.Duration.Companion.milliseconds
import moe.lolosia.keel.api.host.*
import moe.lolosia.keel.api.messaging.*
import moe.lolosia.keel.api.service.ServiceWire

/**
 * 插件侧的消息入口。
 *
 * `post` 直接投给 Host（correlation 为 0，不等待）；`request` 走反向请求，
 * 由 Host 换一次 correlation 转发给目标插件，再把应答原样带回。
 * 两个方向的载荷都是 CBOR 的 [Message]，Host 只填 [Message.source] 并按
 * [Message.target] 路由，不解释 channel 与 payload。
 */
internal object PluginMessenger : Messenger {

    override fun post(message: Message) {
        postToHost(
            HostSubjects.MESSAGE_POST.toULong(),
            ServiceWire.encode(Message.serializer(), message),
        )
    }

    override suspend fun request(message: Message, timeoutMs: Long): ByteArray =
        callHost(
            HostSubjects.MESSAGE_REQUEST.toULong(),
            ServiceWire.encode(Message.serializer(), message),
            timeoutMs.coerceAtLeast(1).milliseconds,
        )
}
