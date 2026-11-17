// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.PluginContext

/** 声明方插件 `moe.lolosia.keel.plugins.hello.HelloApi` 的单例访问器。 */
val PluginContext.helloApi: HelloApi
    get() {
        return publicApi.requireInstance(HelloApiKey) { HelloApiProxy(it) }
    }
