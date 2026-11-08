package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.Plugin
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.TaskHandle
import moe.lolosia.keel.api.annotation.KeelPluginMain
import moe.lolosia.keel.api.command.*
import moe.lolosia.keel.api.web.HttpResponse
import moe.lolosia.keel.api.web.ResponseBody
import moe.lolosia.keel.api.web.RouteDescriptor

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
        context.routes.register(RouteDescriptor("GET", "/hello", "hello#hello")) { _, _ ->
            HttpResponse(body = ResponseBody.Text("hello from keel plugin"))
        }
        // 带 JSON 入参的回显接口，用于验证请求体透传
        context.routes.register(RouteDescriptor("POST", "/echo", "hello#echo")) { request, _ ->
            HttpResponse(
                body = ResponseBody.Text(
                    text = request.body.decodeToString(),
                    contentType = "application/json; charset=utf-8",
                ),
            )
        }
        // 崩溃自检：故意制造一次原生内存故障，验证看门狗报告与插件归因
        context.routes.register(RouteDescriptor("GET", "/crash", "hello#crash")) { _, _ ->
            Log.warn("/crash: 即将触发随机内存故障")
            crashRandomly()
        }
        // 错误传递自检：抛一个带 cause 的异常，验证跨运行时的错误栈与 caused by
        context.routes.register(RouteDescriptor("GET", "/boom", "hello#boom")) { _, _ ->
            throw IllegalStateException(
                "【手工触发】/boom 故意抛出的外层异常，用来验证跨模块错误栈传递是否完整",
                IllegalArgumentException(
                    "【手工触发】/boom 的内层 cause，用来验证 caused by 链是否被保留",
                ),
            )
        }
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

    /** 控制台命令自检：`hello [任意参数]`；不认识的 label 必须返回 false。 */
    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: List<String>,
    ): Boolean {
        if (label != "hello") return false
        sender.sendMessage("hello from keel plugin")
        sender.sendMessage("label=$label args=${args.joinToString(" ")}")
        return true
    }
}
