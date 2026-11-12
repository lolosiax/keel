package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable
import moe.lolosia.keel.api.log.LogRecord

/** 单条查询应答；已被淘汰或尚未分配时为 null。 */
@Serializable
data class LogGetReply(val record: LogRecord? = null)
