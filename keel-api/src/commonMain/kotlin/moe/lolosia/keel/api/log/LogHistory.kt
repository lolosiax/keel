package moe.lolosia.keel.api.log

import kotlinx.coroutines.flow.Flow

/**
 * 日志历史来源。
 *
 * Host 安装读本地环形缓冲的实现；插件安装经事件泵拉取的实现。
 * 所有调用都必须在拥有该运行时的线程上发起（Host 主线程 / 插件 Worker）。
 */
interface LogHistory {

    /**
     * 取一页日志，只返回 id 大于 [cursor] 的记录。
     *
     * [cursor] 为 [Long.MAX_VALUE] 表示只要新日志（历史为空）。
     */
    fun page(cursor: Long, limit: Int, format: LogFormat): LogPage

    /** 取单独一条；id 已被淘汰或尚未分配时返回 null。 */
    fun get(id: Long): LogRecord?

    /**
     * 订阅日志：先把 `id > cursor` 的历史逐页给出，之后按 Host 的合并唤醒续流。
     *
     * 收集方慢时 Flow 挂起，Host 侧只保留「有新日志」的标记，不会堆积正文。
     */
    fun observe(cursor: Long, format: LogFormat): Flow<LogPage>
}
