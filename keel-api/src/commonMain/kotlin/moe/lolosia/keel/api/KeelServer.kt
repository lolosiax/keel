package moe.lolosia.keel.api

import moe.lolosia.keel.api.config.Config
import moe.lolosia.keel.api.event.EventBus
import moe.lolosia.keel.api.messaging.Messenger
import moe.lolosia.keel.api.service.ServiceRegistry
import moe.lolosia.keel.classpath.Classpath

/**
 * 插件可见的宿主服务面。
 *
 * 这里的每个成员在本运行时里都是**本地实现或本地代理**：宿主不会把 Kotlin 对象
 * 直接交给插件，插件也不会把自己的对象交给宿主。
 */
interface KeelServer {

    /**
     * **主线配置**（`application.yaml`）。
     *
     * 与插件自己的 `ctx.config` 是同一个 [Config] 接口；区别只在底层：
     * 插件访问主线配置时，读和写都委托给 Host（见 doc/config-design.md §3.1）。
     */
    val config: Config

    /**
     * Host 自己的资源视图（Host 的 `.keel` / 内嵌 classpath）。
     *
     * `classpathOf(id)` 用于按插件 id 取对方资源；只有 Host 侧注册表能解析，
     * 插件侧请优先使用 `ctx.classpath.of(id)`。
     */
    val classpath: Classpath

    fun classpathOf(pluginId: String): Classpath = classpath.of(pluginId)

    val scheduler: Scheduler

    val events: EventBus

    val services: ServiceRegistry

    val messenger: Messenger

    val plugins: PluginRegistry
}
