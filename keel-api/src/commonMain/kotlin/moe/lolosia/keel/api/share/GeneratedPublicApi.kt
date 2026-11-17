package moe.lolosia.keel.api.share

import moe.lolosia.keel.api.Plugin
import moe.lolosia.keel.api.PluginContext

/**
 * 插件侧 `@PublicApi` 的生成入口，由 `keel-plugin-ksp` 生成固定名 `KeelPluginPublicApi`。
 *
 * Worker 在 `onLoad` 之后、`onEnable` 之前调用 [setup]，停用时在 `onDisable` 之后调用
 * [release]；调用顺序由 Worker 固定，不依赖插件是否覆写生命周期钩子。
 */
interface GeneratedPublicApi {

    /** 上报类型声明、收集分发器，并把 `@PublicApi` 的插件本体登记为单例。 */
    fun setup(plugin: Plugin, context: PluginContext)

    /** 与 [setup] 对称；当前由 Host 在插件离线时统一释放句柄，这里留作本地清理。 */
    fun release(plugin: Plugin)

    /** 没有生成物时使用的空实现。 */
    object Empty : GeneratedPublicApi {
        override fun setup(plugin: Plugin, context: PluginContext) {}
        override fun release(plugin: Plugin) {}
    }
}
