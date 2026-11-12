package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable
import moe.lolosia.keel.api.log.LogFormat

/**
 * 拉取一页日志。
 *
 * [subscriptionId] 为 0 表示一次性拉取（不要求提前订阅）；
 * 非 0 时 Host 校验它属于发起请求的插件。
 */
@Serializable
data class LogFetchRequest(
    val subscriptionId: Long = 0,
    val cursor: Long = 0,
    val format: LogFormat = LogFormat.PLAIN,
    val maxEntries: Int = 256,
    val maxBytes: Int = 128 * 1024,
)
