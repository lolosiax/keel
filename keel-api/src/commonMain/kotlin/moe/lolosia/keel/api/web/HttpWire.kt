package moe.lolosia.keel.api.web

import kotlinx.serialization.Serializable

/**
 * Host → 插件的 HTTP 分发载荷。
 *
 * 只传快照与 routeId；`ApplicationCall` / Ktor 类型不跨 ABI。
 */
@Serializable
data class HttpRouteRequest(
    val routeId: String,
    val pathParameters: Map<String, String> = emptyMap(),
    val request: HttpRequest,
)

/**
 * 插件 → Host 的 HTTP 响应载荷。
 *
 * DEMO 先只支持一次性响应（Text / Bytes / Empty）；流式响应另走 STREAM 协议。
 */
@Serializable
data class HttpRouteResponse(
    val status: Int = 200,
    val contentType: String = "text/plain; charset=utf-8",
    val body: ByteArray = ByteArray(0),
)
