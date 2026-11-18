// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.PluginContext

/** 声明方插件 `moe.lolosia.keel.plugins.hello.HelloPlugin` 的单例访问器；提供者未启用时抛异常。 */
val PluginContext.helloPlugin: HelloPlugin
    get() {
        return publicApi.requireInstance(HelloPluginKey) { HelloPluginProxy(it) }
    }

/** 同 [helloPlugin]；提供者未启用时返回 null（可替换能力按缺席降级时用它）。 */
val PluginContext.helloPluginOrNull: HelloPlugin?
    get() {
        return publicApi.getInstance(HelloPluginKey) { HelloPluginProxy(it) }
    }
