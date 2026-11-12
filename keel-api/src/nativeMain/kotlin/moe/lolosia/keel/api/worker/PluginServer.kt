package moe.lolosia.keel.api.worker

import moe.lolosia.keel.api.*
import moe.lolosia.keel.api.callback.*
import moe.lolosia.keel.api.config.*
import moe.lolosia.keel.api.event.*
import moe.lolosia.keel.api.host.*
import moe.lolosia.keel.api.messaging.*
import moe.lolosia.keel.api.service.*
import moe.lolosia.keel.api.storage.*
import moe.lolosia.keel.classpath.*

/**
 * 插件侧的宿主服务面。
 *
 * 目前接通主线配置、存储、消息与 Host 资源视图；事件总线、服务注册表与插件注册表
 * 在对应运行时落地前访问会直接报错。
 */
internal class PluginServer(
    override val config: Config,
    override val storage: PluginStorage,
) : KeelServer {

    override val classpath: Classpath get() = HostClasspathClient(ClasspathNames.HOST)
    override val scheduler: Scheduler get() = WorkerScheduler
    override val events: EventBus get() = error("事件总线尚未实现")
    override val services: ServiceRegistry get() = error("服务注册表尚未实现")
    override val messenger: Messenger get() = PluginMessenger
    override val plugins: PluginRegistry get() = error("插件注册表尚未实现")
}
