package moe.lolosia.keel.api.worker

import kotlinx.coroutines.CoroutineScope
import moe.lolosia.keel.api.*
import moe.lolosia.keel.api.callback.*
import moe.lolosia.keel.api.config.*
import moe.lolosia.keel.api.host.*
import moe.lolosia.keel.api.service.*
import moe.lolosia.keel.api.web.*
import moe.lolosia.keel.classpath.*

/**
 * 插件上下文的最小实现。
 *
 * 当前提供元信息、日志、调度器、配置与空 classpath；其余能力在对应运行时接入前访问会直接报错。
 */
internal class PluginContextImpl(
    override val metadata: PluginMetadata,
    override val logger: PluginLogger,
) : PluginContext {

    override val server: KeelServer by lazy {
        PluginServer(PluginConfig(host, ConfigScope.MAIN))
    }
    override val host: HostLink get() = HostLinkImpl
    override val modules: ModuleDirectory get() = PluginServices
    override val callbacks: CallbackRegistry get() = error("回调尚未实现")
    override val config: Config by lazy {
        PluginConfig(host, ConfigScope.PLUGIN)
    }
    override val classpath: Classpath = EmptyClasspath
    override val routes: Routes get() = PluginRoutes
    override val scheduler: Scheduler get() = WorkerScheduler
    override val scope: CoroutineScope get() = error("协程作用域尚未实现")
}
