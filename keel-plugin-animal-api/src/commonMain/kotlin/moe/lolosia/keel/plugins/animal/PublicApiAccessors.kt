package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.PluginContext

/**
 * 声明方插件单例的访问器。
 *
 * 与 `ctx.<service>` 同形：显式 Key、无反射；返回的是经 Host 路由的本地代理。
 */
val PluginContext.animalPlugin: AnimalPlugin
    get() {
        val directory = publicApi
        return directory.requireInstance(AnimalPluginKey) { AnimalPluginProxy(it, directory) }
    }
