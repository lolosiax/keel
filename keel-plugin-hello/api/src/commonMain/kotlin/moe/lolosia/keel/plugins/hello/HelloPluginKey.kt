// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.share.PublicApiKey

/** `moe.lolosia.keel.plugins.hello.HelloPlugin` 的跨插件类型 id；声明方与消费方必须一致。 */
const val HELLO_PLUGIN_TYPE_ID: String = "moe.lolosia.keel.plugins.hello.HelloPlugin"

/** `moe.lolosia.keel.plugins.hello.HelloPlugin` 的查找键。 */
val HelloPluginKey = PublicApiKey<HelloPlugin>(HELLO_PLUGIN_TYPE_ID)
