package moe.lolosia.keel.plugins.console

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.take
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.log.LogTextFormat

/**
 * 把日志归档接到窗口。
 *
 * 走 `Log.observe(ANSI)`：Host 渲染，颜色与列宽和 Host 控制台逐字节一致。
 * 首次回放受 [maxReplayLines] 限制；截断后从 `-1` 重新订阅，只跟后续新日志。
 */
internal class LogPump(
    private val context: PluginContext,
    private val queue: LogQueue,
    private val startId: Long,
    private val maxReplayLines: Int,
) {

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = context.scope.launch { collect() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun collect() {
        var cursor = startId
        if (cursor >= 0 && maxReplayLines > 0) {
            Log.observe(LogTextFormat.ANSI, cursor)
                .take(maxReplayLines)
                .collect { queue.offer(it.second) }
        }
        cursor = if (cursor >= 0 && maxReplayLines > 0) -1 else cursor
        Log.observe(LogTextFormat.ANSI, cursor).collect { queue.offer(it.second) }
    }
}
