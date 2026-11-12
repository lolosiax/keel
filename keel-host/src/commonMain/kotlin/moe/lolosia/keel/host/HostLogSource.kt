package moe.lolosia.keel.host

import moe.lolosia.keel.api.log.*

/**
 * Host 侧日志历史：直接读本地环形缓冲。
 *
 * 文本视图在这里渲染，保证与 Console、日志文件使用同一套格式。
 */
internal object HostLogSource : LogHistory {

    override fun page(cursor: Long, limit: Int, format: LogFormat): LogPage {
        val records = LogArchive.page(cursor, limit)
        val firstId = LogArchive.firstId()
        val nextId = LogArchive.nextId()
        // 游标被淘汰：请求的位置早于当前最老一条，出现缺口
        val dropped = cursor != Long.MAX_VALUE && cursor + 1 < firstId

        return when (format) {
            LogFormat.RAW -> LogPage(firstId, nextId, dropped, records = records)

            LogFormat.PLAIN, LogFormat.ANSI -> {
                val textFormat =
                    if (format == LogFormat.ANSI) LogTextFormat.ANSI else LogTextFormat.PLAIN
                LogPage(
                    firstId = firstId,
                    nextId = nextId,
                    dropped = dropped,
                    lines = records.map { LogLine(it.id, LogRecordFormat.render(it, textFormat)) },
                )
            }
        }
    }

    override fun get(id: Long): LogRecord? = LogArchive.get(id)
}
