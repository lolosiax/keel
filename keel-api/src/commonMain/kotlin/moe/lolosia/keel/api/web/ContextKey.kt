// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.api.web

import moe.lolosia.keel.api.share.PublicApiKey

/** `moe.lolosia.keel.api.web.Context` 的跨插件类型 id；声明方与消费方必须一致。 */
const val CONTEXT_TYPE_ID: String = "keel.context"

/** `moe.lolosia.keel.api.web.Context` 的查找键。 */
val ContextKey = PublicApiKey<Context>(CONTEXT_TYPE_ID)
