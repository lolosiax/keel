package moe.lolosia.keel.api.web

import kotlinx.serialization.Serializable

/**
 * Host → 插件的 HTTP 分发载荷。
 *
 * 只传快照、routeId 与已解析的用户；`ApplicationCall` / Ktor 类型不跨 ABI。
 * [user] 为 `null` 表示未登录（公开路由）或 Host 尚未接入认证。
 */
@Serializable
data class HttpRouteRequest(
    val routeId: String,
    val pathParameters: Map<String, String> = emptyMap(),
    val request: HttpRequest,
    val user: UserSnapshot? = null,
)

/**
 * 插件 → Host 的 HTTP 响应载荷。
 *
 * DEMO 先只支持一次性响应（Text / Bytes / Empty）；流式响应另走 STREAM 协议。
 *
 * [headers] 用于 WebDAV 这类必须回自定义头（`DAV` / `Allow` / `ETag` / `Content-Range`）的协议；
 * `Content-Type` / `Content-Length` 由 Host 按 [contentType] 与 [body] 统一写入，这里不要重复给。
 */
@Serializable
data class HttpRouteResponse(
    val status: Int = 200,
    val contentType: String = "text/plain; charset=utf-8",
    val body: ByteArray = ByteArray(0),
    val headers: Map<String, String> = emptyMap(),
)
