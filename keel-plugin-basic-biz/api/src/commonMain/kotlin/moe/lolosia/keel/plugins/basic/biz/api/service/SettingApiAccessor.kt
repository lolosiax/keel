// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.basic.biz.api.service

import moe.lolosia.keel.api.PluginContext

/** 声明方插件 `moe.lolosia.keel.plugins.basic.biz.api.service.SettingApi` 的单例访问器；提供者未启用时抛异常。 */
val PluginContext.settingApi: SettingApi
    get() {
        return publicApi.requireInstance(SettingApiKey) { SettingApiProxy(it) }
    }

/** 同 [settingApi]；提供者未启用时返回 null（可替换能力按缺席降级时用它）。 */
val PluginContext.settingApiOrNull: SettingApi?
    get() {
        return publicApi.getInstance(SettingApiKey) { SettingApiProxy(it) }
    }
