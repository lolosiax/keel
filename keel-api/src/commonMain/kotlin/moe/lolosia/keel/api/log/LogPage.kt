package moe.lolosia.keel.api.log

import kotlinx.serialization.Serializable

/**
 * 一次日志拉取的结果。
 *
 * [records] 与 [lines] 只会有一个非空：RAW 视图填前者，文本视图填后者。
 *
 * @property firstId 当前缓冲区最老的 id
 * @property nextId 下一个待分配的 id
 * @property dropped 请求的游标已被淘汰，出现缺口
 */
@Serializable
data class LogPage(
    val firstId: Long,
    val nextId: Long,
    val dropped: Boolean = false,
    val records: List<LogRecord> = emptyList(),
    val lines: List<LogLine> = emptyList(),
)
