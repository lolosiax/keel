package moe.lolosia.keel.api.log

import kotlinx.serialization.Serializable

/** 文本视图的一行：id 与 Host 渲染好的文本。 */
@Serializable
data class LogLine(val id: Long, val text: String)
