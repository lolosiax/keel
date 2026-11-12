package moe.lolosia.keel.api.worker

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import moe.lolosia.keel.api.host.HostRequestException
import moe.lolosia.keel.api.log.*

/** 每页最多拉取多少条；与 Host 的默认值一致。 */
private const val PAGE_ENTRIES = 256

/**
 * 插件侧日志历史：经事件泵向 Host 拉取。
 *
 * 批量 / 单条走请求；订阅走「唤醒 + 按游标拉取」，插件 inbox 里最多只有一条唤醒。
 */
internal object PluginLogSource : LogHistory {

    override fun page(cursor: Long, limit: Int, format: LogFormat): LogPage =
        LogSubscriptions.page(subscriptionId = 0, cursor = cursor, limit = limit, format = format)

    override fun get(id: Long): LogRecord? = LogSubscriptions.get(id)

    override fun observe(cursor: Long, format: LogFormat): Flow<LogPage> = flow {
        val handle = LogSubscriptions.open(cursor)
        try {
            var current = handle.cursor
            while (true) {
                while (true) {
                    // 插件停用 / Host 不再应答时安静结束：观察是一条后台流，不该让异常逃逸
                    val page = try {
                        LogSubscriptions.page(handle.id, current, PAGE_ENTRIES, format)
                    } catch (e: HostRequestException) {
                        return@flow
                    }
                    if (page.lines.isEmpty() && page.records.isEmpty()) break
                    emit(page)
                    current = page.nextId - 1
                }
                handle.awaitWake()
            }
        } finally {
            LogSubscriptions.close(handle)
        }
    }
}
