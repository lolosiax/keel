package moe.lolosia.keel.api.worker

import kotlinx.coroutines.CoroutineScope
import moe.lolosia.keel.api.*
import moe.lolosia.keel.api.callback.*
import moe.lolosia.keel.api.config.*
import moe.lolosia.keel.api.service.*
import moe.lolosia.keel.classpath.*

/**
 * 插件上下文的最小实现。
 *
 * 当前只提供元信息、日志、调度器与空 classpath；其余能力在对应运行时接入前访问会直接报错。
 */
internal class PluginContextImpl(
    override val metadata: PluginMetadata,
    override val logger: PluginLogger,
) : PluginContext {

    override val server: KeelServer get() = error("KeelServer 尚未实现")
    override val modules: ModuleDirectory get() = error("跨模块调用尚未实现")
    override val callbacks: CallbackRegistry get() = error("回调尚未实现")
    override val config: Config get() = error("配置尚未实现")
    override val classpath: Classpath = EmptyClasspath
    override val scheduler: Scheduler get() = WorkerScheduler
    override val scope: CoroutineScope get() = error("协程作用域尚未实现")
}
