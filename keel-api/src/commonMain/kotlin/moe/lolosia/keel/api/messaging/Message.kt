package moe.lolosia.keel.api.messaging

/**
 * 跨插件消息。载荷是紧凑二进制（Protobuf / CBOR），不走 JSON。
 *
 * @property correlationId 请求 / 应答配对；主动通知可为空
 */
data class Message(
    val target: String,
    val channel: String,
    val payload: ByteArray,
    val correlationId: String? = null,
)
