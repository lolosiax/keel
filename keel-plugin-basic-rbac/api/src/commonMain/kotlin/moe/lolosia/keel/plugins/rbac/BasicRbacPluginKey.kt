// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.rbac

import moe.lolosia.keel.api.share.PublicApiKey

/** `moe.lolosia.keel.plugins.rbac.BasicRbacPlugin` 的跨插件类型 id；声明方与消费方必须一致。 */
const val BASIC_RBAC_PLUGIN_TYPE_ID: String = "moe.lolosia.keel.plugins.rbac.BasicRbacPlugin"

/** `moe.lolosia.keel.plugins.rbac.BasicRbacPlugin` 的查找键。 */
val BasicRbacPluginKey = PublicApiKey<BasicRbacPlugin>(BASIC_RBAC_PLUGIN_TYPE_ID)
