// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.api.web

import moe.lolosia.keel.api.share.PublicApiKey

/** `moe.lolosia.keel.api.web.WebContext` 的跨插件类型 id；声明方与消费方必须一致。 */
const val WEB_CONTEXT_TYPE_ID: String = "moe.lolosia.keel.api.web.WebContext"

/** `moe.lolosia.keel.api.web.WebContext` 的查找键。 */
val WebContextKey = PublicApiKey<WebContext>(WEB_CONTEXT_TYPE_ID)
