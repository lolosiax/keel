// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.PluginContext

/** 声明方插件 `moe.lolosia.keel.plugins.animal.AnimalPlugin` 的单例访问器；提供者未启用时抛异常。 */
val PluginContext.animalPlugin: AnimalPlugin
    get() {
        val directory = publicApi
        return publicApi.requireInstance(AnimalPluginKey) { AnimalPluginProxy(it, directory) }
    }

/** 同 [animalPlugin]；提供者未启用时返回 null（可替换能力按缺席降级时用它）。 */
val PluginContext.animalPluginOrNull: AnimalPlugin?
    get() {
        val directory = publicApi
        return publicApi.getInstance(AnimalPluginKey) { AnimalPluginProxy(it, directory) }
    }
