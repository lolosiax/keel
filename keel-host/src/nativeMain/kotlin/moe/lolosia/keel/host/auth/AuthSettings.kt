package moe.lolosia.keel.host.auth

import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Host 认证 / 授权中间件的配置。
 *
 * M1 先从环境变量读（与 `DatabaseSettings` 同一套过渡做法）；`Config` 运行时落地后
 * 改为读 `application.yaml` 的 `auth` 段，字段与默认值保持不变。
 *
 * - `KEEL_AUTH_CACHE_TTL_MS`：认证 / 授权快照的缓存时长，默认 30s；
 * - `KEEL_AUTH_TIMEOUT_MS`：向插件发起 SPI 请求的超时，默认 2s；
 * - `KEEL_AUTH_WHITELIST_EXACT` / `KEEL_AUTH_WHITELIST_PREFIX`：逗号分隔，覆盖默认白名单。
 */
internal class AuthSettings(
    val cacheTtlMs: Long,
    val timeoutMs: Long,
    val whitelistExact: Set<String>,
    val whitelistPrefix: List<String>,
) {

    /**
     * 白名单只免「没登录就 401」，不免权限判定。
     */
    fun isWhitelisted(path: String): Boolean =
        path in whitelistExact || whitelistPrefix.any { path.startsWith(it) }

    companion object {

        /** 与 backend 保持一致：登录 / 注册 / 验证码等不需要会话的接口。 */
        private val DEFAULT_EXACT = setOf(
            "/api/sse",
            "/api/sse/registry",
            "/api/login",
            "/api/captcha",
            "/api/verify",
            "/api/register",
            "/api/user/updatePassword",
            "/api/user/avatar",
        )

        /** 取件类接口：`<img src>` 带不了请求头，由接口自己校验签名 / 短码。 */
        private val DEFAULT_PREFIX = listOf(
            "/api/oss/show/",
            "/api/oss/get/",
            "/api/user/avatar/get/",
            "/api/files/thumb",
            "/api/files/raw",
            "/api/share/",
        )

        fun fromEnvironment(): AuthSettings = AuthSettings(
            cacheTtlMs = environment("KEEL_AUTH_CACHE_TTL_MS")?.toLongOrNull() ?: 30_000L,
            timeoutMs = environment("KEEL_AUTH_TIMEOUT_MS")?.toLongOrNull() ?: 2_000L,
            whitelistExact = environmentList("KEEL_AUTH_WHITELIST_EXACT")?.toSet() ?: DEFAULT_EXACT,
            whitelistPrefix = environmentList("KEEL_AUTH_WHITELIST_PREFIX") ?: DEFAULT_PREFIX,
        )

        private fun environment(name: String): String? =
            getenv(name)?.toKString()?.trim()?.takeIf { it.isNotEmpty() }

        private fun environmentList(name: String): List<String>? =
            environment(name)?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
    }
}
