package moe.lolosia.keel.plugins.basic.biz

import kotlinx.serialization.Serializable
import moe.lolosia.keel.api.web.*
import moe.lolosia.keel.plugins.basic.biz.api.dto.*
import moe.lolosia.keel.plugins.basic.biz.captcha.CaptchaStore
import moe.lolosia.keel.plugins.basic.biz.captcha.createMathCaptcha
import moe.lolosia.keel.plugins.basic.biz.db.AccountRepository

/**
 * 登录 / 登出 / 图形验证码 / 注册。
 *
 * 路径与入参形状照 backend 的 `LoginController`；这五个里除 `/logout` 外都在 Host 的
 * 鉴权白名单里（见 `AuthSettings.DEFAULT_EXACT`），所以注册前不需要会话。
 */
@Controller("/api")
object AuthController {

    @Serializable
    data class LoginReq(
        val userName: String,
        val password: String? = null,
    )

    /** 前端把 [image] 拼成 `data:image/png;base64,<image>`。 */
    @Serializable
    data class CaptchaResult(val image: String, val id: String)

    @Serializable
    data class VerifyReq(
        val id: String,
        val code: String,
        val userName: String? = null,
        val email: String? = null,
    )

    @Serializable
    data class MessageResult(val message: String = "success")

    @POST("/login")
    suspend fun login(ctx: Context, @Body param: LoginReq): LoginResult =
        AuthService.login(
            account = param.userName,
            password = param.password,
            sessionTtlSeconds = BasicBizSettings(ctx.plugin).load().sessionTtlSeconds,
        )

    @POST("/logout")
    suspend fun logout(ctx: Context): MessageResult {
        val token = bearerToken(ctx) ?: return MessageResult("SessionId哪去了？")
        AccountRepository.revoke(token)
        return MessageResult("success")
    }

    @POST("/captcha")
    suspend fun captcha(): CaptchaResult {
        val generated = createMathCaptcha()
        val id = newIdentifier()
        CaptchaStore.put(id, generated.answer)
        return CaptchaResult(image = generated.imageBase64, id = id)
    }

    @POST("/verify")
    suspend fun verify(@Body param: VerifyReq): MessageResult {
        val expected = CaptchaStore.take(param.id)
            ?: throw ApiException(400, "验证码已过期，请点击图片刷新")
        if (expected != param.code.trim()) throw ApiException(400, "验证码错误，请点击图片刷新")
        return MessageResult("success")
    }

    @POST("/register")
    suspend fun register(@Body param: RegisterReq): UserRow = AuthService.register(param)

    private fun bearerToken(ctx: Context): String? = ctx.request.headers.entries
        .firstOrNull { it.key.equals("Authorization", ignoreCase = true) }
        ?.value
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substring(7)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}
