// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.basic.biz.api.service

import moe.lolosia.keel.api.PluginContext

/** 声明方插件 `moe.lolosia.keel.plugins.basic.biz.api.service.UserDirectory` 的单例访问器。 */
val PluginContext.userDirectory: UserDirectory
    get() {
        return publicApi.requireInstance(UserDirectoryKey) { UserDirectoryProxy(it) }
    }
