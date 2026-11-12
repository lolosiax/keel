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
 * 资源相关能力（[cacheDir] / [materialize] / [classpath]）经反向请求交给 Host；
 * 其余尚未接入的能力访问时会直接报错。
 */
internal class PluginContextImpl(
    override val metadata: PluginMetadata,
    override val logger: PluginLogger,
    override val cacheDir: String,
) : PluginContext {

    override val server: KeelServer by lazy {
        PluginServer(PluginConfig(host, ConfigScope.MAIN), PluginStorageImpl(host))
    }
    override val host: HostLink get() = HostLinkImpl
    override val modules: ModuleDirectory get() = PluginServices
    override val callbacks: CallbackRegistry get() = error("回调尚未实现")
    override val config: Config by lazy {
        PluginConfig(host, ConfigScope.PLUGIN)
    }
    override val classpath: Classpath by lazy { PluginClasspath() }
    override val routes: Routes get() = PluginRoutes
    override val scheduler: Scheduler get() = WorkerScheduler
    override val scope: CoroutineScope get() = WorkerScope

    override fun materialize(path: String): String =
        ServiceWire.decode(
            MaterializeReply.serializer(),
            host.request(
                HostSubjects.MATERIALIZE,
                ServiceWire.encode(MaterializeRequest.serializer(), MaterializeRequest(path)),
            ),
        ).path
}
