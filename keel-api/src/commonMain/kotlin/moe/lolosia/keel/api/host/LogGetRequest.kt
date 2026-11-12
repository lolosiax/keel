package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable

/** 取单独一条日志记录。 */
@Serializable
data class LogGetRequest(val id: Long)
