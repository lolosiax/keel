// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.PluginContext

/** 声明方插件 `moe.lolosia.keel.plugins.animal.AnimalPlugin` 的单例访问器。 */
val PluginContext.animalPlugin: AnimalPlugin
    get() {
        val directory = publicApi
        return directory.requireInstance(AnimalPluginKey) { AnimalPluginProxy(it, directory) }
    }
