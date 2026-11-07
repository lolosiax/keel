package moe.lolosia.keel.api.web

import kotlinx.serialization.Serializable

/**
 * 一次请求的只读快照，由 Host 从 Ktor 的 `ApplicationCall` 提取后传入插件。
 *
 * 不能在插件里拿到 `ApplicationCall`：它绑定 Host 的协程作用域与线程，
 * 跨运行时使用会直接崩。
 */
@Serializable
data class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, List<String>> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val remoteAddress: String? = null,
    val traceId: String? = null,
)
