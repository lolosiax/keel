package moe.lolosia.keel.host

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import moe.lolosia.keel.api.log.*

/** 订阅时每页最多读取多少条。 */
private const val OBSERVE_PAGE = 256

/**
 * Host 侧日志历史：直接读本地环形缓冲。
 *
 * 文本视图在这里渲染，保证与 Console、日志文件使用同一套格式；
 * 订阅用本地协程通道接收合并唤醒，不经过事件泵。
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

    override fun observe(cursor: Long, format: LogFormat): Flow<LogPage> = flow {
        val wake = Channel<Unit>(Channel.CONFLATED)
        val subscriptionId = registerLocalLogWake(wake)
        try {
            var current = cursor
            while (true) {
                while (true) {
                    val page = page(current, OBSERVE_PAGE, format)
                    if (page.lines.isEmpty() && page.records.isEmpty()) break
                    emit(page)
                    current = page.nextId - 1
                }
                wake.receive()
            }
        } finally {
            unregisterLocalLogWake(subscriptionId)
        }
    }
}
