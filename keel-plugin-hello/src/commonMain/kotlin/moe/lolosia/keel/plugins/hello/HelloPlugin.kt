package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.Plugin
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.TaskHandle
import moe.lolosia.keel.api.annotation.KeelPluginMain

/**
 * 最小示例插件：演示生命周期与上下文注入。
 *
 * 元信息来自 `plugin.json`（经 [PluginContext.metadata] 注入），
 * 服务注册由 KSP 生成的 Worker 入口完成，这里只保留生命周期钩子。
 */
@KeelPluginMain
class HelloPlugin : Plugin {

    private lateinit var context: PluginContext

    override fun onLoad(context: PluginContext) {
        this.context = context
        Log.info("loaded ${context.metadata.id}")
    }

    override fun onEnable() {
        Log.info("enabled")
        logLevelSamples()
        scheduleSamples()
    }

    /** 调度器自检：延时任务 + 周期任务，周期任务第 3 次自行取消。 */
    private fun scheduleSamples() {
        val scheduler = context.scheduler
        scheduler.schedule(30) { Log.info("调度器自检：延时 30ms 任务") }

        var ticks = 0
        var handle: TaskHandle? = null
        handle = scheduler.schedule(20, 50) {
            ticks++
            Log.info("调度器自检：周期任务第 $ticks 次")
            if (ticks >= 3) handle?.cancel()
        }
    }

    /** 日志等级配色自检：每个级别各打一条。 */
    private fun logLevelSamples() {
        Log.trace("日志等级自检：TRACE")
        Log.debug("日志等级自检：DEBUG")
        Log.info("日志等级自检：INFO")
        Log.warn("日志等级自检：WARN")
        Log.error("日志等级自检：ERROR")
        Log.fatal("日志等级自检：FATAL")
    }

    override fun onDisable() {
        Log.info("disabled")
    }
}
