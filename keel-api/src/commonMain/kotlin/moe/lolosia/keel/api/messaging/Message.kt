package moe.lolosia.keel.api.messaging

import kotlinx.serialization.*

/**
 * 跨插件消息。载荷是紧凑二进制（Protobuf / CBOR），不走 JSON。
 *
 * Host 只负责按 [target] 路由，不解释 [channel] 与 [payload]；
 * 两个插件之间的协议由 channel 约定，版本不匹配时由接收方自己拒绝。
 *
 * @property target 目标插件的 manifest id（`plugin.json` 的 `id`）
 * @property channel 自由字符串，由收发双方约定；Host 原样透传
 * @property payload 原始字节；CBOR / 纯文本都行
 * @property source 接收方看到的发送方 manifest id；发送方留空，由 Host 填写
 */
@Serializable
data class Message(
    val target: String,
    val channel: String,
    val payload: ByteArray,
    val source: String = "",
)
