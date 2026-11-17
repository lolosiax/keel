// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.share.PublicApiKey

/** `moe.lolosia.keel.plugins.hello.HelloApi` 的跨插件类型 id；声明方与消费方必须一致。 */
const val HELLO_API_TYPE_ID: String = "moe.lolosia.keel.plugins.hello.HelloApi"

/** `moe.lolosia.keel.plugins.hello.HelloApi` 的查找键。 */
val HelloApiKey = PublicApiKey<HelloApi>(HELLO_API_TYPE_ID)
