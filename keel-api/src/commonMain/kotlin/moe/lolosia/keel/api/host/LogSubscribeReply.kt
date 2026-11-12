package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable

/**
 * 订阅应答。
 *
 * @property subscriptionId 后续 `LOG_FETCH` / `LOG_UNSUBSCRIBE` 使用的标识
 * @property cursor 已解析的起始游标；`-1` 会被换成「当前最新 id」
 * @property firstId 当前缓冲区最老 id
 * @property nextId 下一个待分配 id
 */
@Serializable
data class LogSubscribeReply(
    val subscriptionId: Long,
    val cursor: Long,
    val firstId: Long,
    val nextId: Long,
)
