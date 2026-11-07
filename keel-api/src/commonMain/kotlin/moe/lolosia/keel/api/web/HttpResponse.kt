package moe.lolosia.keel.api.web

/**
 * 显式响应。普通 handler 直接返回业务对象即可，由 KSP 生成的适配器包成 JSON；
 * 需要控制状态码 / 头 / 流时返回这个类型。
 *
 * @property envelope 是否让 Host 的 `EnvelopeInterceptor` 处理 JSON 信封
 */
data class HttpResponse(
    val status: Int = 200,
    val headers: Map<String, String> = emptyMap(),
    val body: ResponseBody = ResponseBody.Empty,
    val envelope: Boolean = true,
)
