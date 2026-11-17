// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.PluginContext

/** 声明方插件 `moe.lolosia.keel.plugins.hello.HelloPlugin` 的单例访问器。 */
val PluginContext.helloPlugin: HelloPlugin
    get() {
        return publicApi.requireInstance(HelloPluginKey) { HelloPluginProxy(it) }
    }
