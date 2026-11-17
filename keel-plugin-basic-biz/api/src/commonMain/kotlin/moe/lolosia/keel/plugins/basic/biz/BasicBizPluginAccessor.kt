// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.basic.biz

import moe.lolosia.keel.api.PluginContext

/** 声明方插件 `moe.lolosia.keel.plugins.basic.biz.BasicBizPlugin` 的单例访问器。 */
val PluginContext.basicBizPlugin: BasicBizPlugin
    get() {
        return publicApi.requireInstance(BasicBizPluginKey) { BasicBizPluginProxy(it) }
    }
