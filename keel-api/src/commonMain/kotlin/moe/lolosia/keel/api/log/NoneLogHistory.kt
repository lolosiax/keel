package moe.lolosia.keel.api.log

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/** 未安装来源时的空实现：历史为空、没有单条记录、订阅立即结束。 */
object NoneLogHistory : LogHistory {

    override fun page(cursor: Long, limit: Int, format: LogFormat): LogPage =
        LogPage(firstId = 1, nextId = 1)

    override fun get(id: Long): LogRecord? = null

    override fun observe(cursor: Long, format: LogFormat): Flow<LogPage> = emptyFlow()
}
