package moe.lolosia.keel.plugins.hello

import kotlinx.coroutines.*
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.Plugin
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.TaskHandle
import moe.lolosia.keel.api.annotation.KeelPluginMain
import moe.lolosia.keel.api.auth.*
import moe.lolosia.keel.api.command.*
import moe.lolosia.keel.api.config.*
import moe.lolosia.keel.api.log.LogTextFormat
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
        context.routes.register(RouteDescriptor("GET", "/hello", "hello#hello")) {
            HttpResponse(body = ResponseBody.Text("hello from keel plugin"))
        }
        // 带 JSON 入参的回显接口，用于验证请求体透传
        context.routes.register(RouteDescriptor("POST", "/echo", "hello#echo")) { ctx ->
            HttpResponse(
                body = ResponseBody.Text(
                    text = ctx.request.body.decodeToString(),
                    contentType = "application/json; charset=utf-8",
                ),
            )
        }
        // 崩溃自检：故意制造一次原生内存故障，验证看门狗报告与插件归因
        context.routes.register(RouteDescriptor("GET", "/crash", "hello#crash")) {
            Log.warn("/crash: 即将触发随机内存故障")
            crashRandomly()
        }
        // 错误传递自检：抛一个带 cause 的异常，验证跨运行时的错误栈与 caused by
        context.routes.register(RouteDescriptor("GET", "/boom", "hello#boom")) {
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
        checkScope()
        checkConfig()
        checkResources()
        checkStorage()
        checkDatabase()
        checkService()
        checkLogArchive()
    }

    /**
     * 日志归档自检：批量重放与单条查询都经事件泵向 Host 拉取。
     *
     * 此时归档里已经有启动横幅、Loader 与本插件的前几条日志，
     * 所以 `history` 不为空、`get` 能取到同一条记录的 RAW 对象。
     */
    private fun checkLogArchive() {
        val history = Log.history(LogTextFormat.PLAIN, limit = 3)
        val last = history.lastOrNull()
        val record = last?.let { Log.get(it.first) }
        Log.info(
            "日志归档自检：history=${history.size} id=${last?.first} " +
                "module=${record?.module} level=${record?.level} banner=${record?.banner}"
        )
    }

    /** 协程自检：`ctx.scope` 绑定在 Worker 线程上，`delay` 由插件定时器驱动。 */
    private fun checkScope() {
        context.scope.launch {
            Log.info("协程自检：启动")
            delay(60)
            Log.info("协程自检：delay 后在 Worker 线程恢复")
        }
    }

    /** 存储自检：写入 / 读回插件命名空间，并确认越界路径被 Host 拒绝。 */
    private fun checkStorage() {
        runBlocking {
            val path = "check/hello.txt"
            context.server.storage.write(path, "hello storage".encodeToByteArray())
            val text = context.server.storage.read(path)?.decodeToString()
            val escapeRejected = runCatching { context.server.storage.read("../escape") }.isFailure
            Log.info("存储自检：read=$text 越界拒绝=$escapeRejected")
        }
    }

    /** 配置自检：启动期（Host 还在等 START 的 COMPLETE）就要能读到自己的配置。 */
    private fun checkConfig() {
        val config = context.config.read<HelloConfig>()
        Log.info("配置自检：greeting=${config.greeting} repeat=${config.repeat}")
    }

    /** 资源自检：缓存目录、资源物化与自身 classpath 读取，越界路径必须被拒绝。 */
    private fun checkResources() {
        Log.info("资源自检：cacheDir=${context.cacheDir}")
        val text = context.classpath.read("hello.txt")?.decodeToString()?.trim()
        val file = context.materialize("hello.txt")
        // 目录前缀会整棵写出（`web/` 交给 WebView 这类场景），返回目录路径
        val webDir = context.materialize("web")
        // 同一路径重复物化必须落到同一份文件；`..` 由 Host 拒绝
        val same = context.materialize("hello.txt") == file
        val rejected = runCatching { context.materialize("../escape") }.isFailure
        Log.info(
            "资源自检：classpath=$text materialize=$file web=$webDir 幂等=$same 越界拒绝=$rejected"
        )
    }

    /** `plugin.json` 声明了 `provides: ["authn"]`，Host 会用它解析 Bearer token。 */
    override fun authenticator(): Authenticator = HelloAuth

    /** `plugin.json` 声明了 `provides: ["authz"]`，Host 会用它取授权快照。 */
    override fun authorizer(): Authorizer = HelloAuth

    /**
     * 服务自检：用生成的客户端走一次 Host 往返调用（实现见 nativeMain）。
     *
     * 生成代码只存在于 native 目标编译里，所以调用点用 expect / actual 隔离，
     * 避免 commonMain 的元数据编译依赖 KSP 产物。
     */
    private fun checkService() {
        checkHelloService(context)
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
