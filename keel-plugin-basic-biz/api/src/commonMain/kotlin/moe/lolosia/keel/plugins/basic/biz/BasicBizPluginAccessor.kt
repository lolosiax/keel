// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.basic.biz

import moe.lolosia.keel.api.PluginContext

/** 声明方插件 `moe.lolosia.keel.plugins.basic.biz.BasicBizPlugin` 的单例访问器；提供者未启用时抛异常。 */
val PluginContext.basicBizPlugin: BasicBizPlugin
    get() {
        return publicApi.requireInstance(BasicBizPluginKey) { BasicBizPluginProxy(it) }
    }

/** 同 [basicBizPlugin]；提供者未启用时返回 null（可替换能力按缺席降级时用它）。 */
val PluginContext.basicBizPluginOrNull: BasicBizPlugin?
    get() {
        return publicApi.getInstance(BasicBizPluginKey) { BasicBizPluginProxy(it) }
    }
