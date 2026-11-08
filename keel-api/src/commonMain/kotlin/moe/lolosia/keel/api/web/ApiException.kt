package moe.lolosia.keel.api.web

/**
 * 带 HTTP 状态码的业务异常：[status] 同时作为 HTTP 状态码与信封的 `code`。
 *
 * 鉴权失败用 `401`；前端对 `401 / 403` 会清登录态并跳回登录页。
 */
open class ApiException(
    val status: Int,
    message: String,
) : RuntimeException(message)
