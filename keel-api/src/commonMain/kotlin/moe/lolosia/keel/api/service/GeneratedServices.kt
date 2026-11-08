package moe.lolosia.keel.api.service

import moe.lolosia.keel.api.PluginContext

/**
 * KSP 生成的服务注册表（固定名 `KeelPluginServices`）。
 *
 * 没有 `@ModuleServiceProvider` 的插件也会生成空实现，Worker 入口因此可以无条件引用它。
 */
interface GeneratedServices {

    /**
     * 构造实现类并登记；在 `onLoad` 之后、`onEnable` 之前调用。
     */
    fun register(binder: ServiceBinder, context: PluginContext)
}
