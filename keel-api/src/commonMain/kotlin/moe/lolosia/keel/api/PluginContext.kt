package moe.lolosia.keel.api

import kotlinx.coroutines.*
import moe.lolosia.keel.api.callback.*
import moe.lolosia.keel.api.config.*
import moe.lolosia.keel.api.service.*
import moe.lolosia.keel.api.web.*
import moe.lolosia.keel.classpath.*

/**
 * 宿主注入给插件的运行时上下文。
 *
 * 这里的对象都活在**插件自己的运行时**里，由插件的 Worker 线程构造；
 * 不要把它（或其字段）传出当前运行时，传给别的插件一律走事件 / 消息。
 */
interface PluginContext {

    /**
     * 本插件的元信息，来自 `plugin.json`，由宿主读取后注入。
     */
    val metadata: PluginMetadata

    val server: KeelServer

    /**
     * 跨模块入口。`ctx.modules.require(QueueApiKey)` 拿到的是本地代理，
     * 方法调用经事件泵转发，不跨运行时传递 Kotlin 对象或 continuation。
     */
    val modules: ModuleDirectory

    /**
     * 本地回调表；生成的客户端用它把函数类型参数换成数字 id。
     */
    val callbacks: CallbackRegistry

    /**
     * 本插件的配置（`config/plugins/<id>.yaml`）；读取走本地快照，写入经 Host 落盘。
     */
    val config: Config

    /**
     * 本插件的资源视图：自己的 `.keel` 优先，缺失时回落 Host。
     *
     * 跨插件访问用 `ctx.classpath.of("other")`，它只返回不透明引用，不暴露
     * 对方的 `.keel` 路径；插件卸载后旧引用读取会立即失效。
     */
    val classpath: Classpath

    /**
     * 本插件的 HTTP 路由表：注册后由 Worker 在启用时统一发给 Host。
     */
    val routes: Routes

    /** 兼容日志入口；新代码用 [Log]，才能拿到调用点来源。 */
    val logger: PluginLogger

    val scheduler: Scheduler

    /**
     * 绑定在插件 Worker 调度器上的作用域：协程只在本线程执行，
     * 不要在别的线程恢复，也不要跨运行时传递 continuation。
     */
    val scope: CoroutineScope
}
