package moe.lolosia.keel.api.web

import kotlinx.serialization.Serializable

/**
 * 统一响应信封，结构固定为 `{code, msg, data}`（前端按这三个键解包）。
 *
 * 控制器不必自己拼：返回值适配器会统一包一层；需要直接返回信封时构造本类型即可。
 */
@Serializable
data class ApiResult<T>(
    val code: Int = 200,
    val msg: String = "success",
    val data: T? = null,
) {
    companion object {
        fun <T> ok(data: T? = null, msg: String = "success"): ApiResult<T> =
            ApiResult(200, msg, data)

        fun <T> err(code: Int, msg: String): ApiResult<T> = ApiResult(code, msg, null)
    }
}
