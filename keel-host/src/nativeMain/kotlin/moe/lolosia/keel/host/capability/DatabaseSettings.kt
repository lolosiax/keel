package moe.lolosia.keel.host.capability

import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Host 主线配置里的 `database` 段。
 *
 * M0 先读环境变量（`KEEL_DB_URL` / `KEEL_DB_USER` / `KEEL_DB_PASSWORD` /
 * `KEEL_DB_MAX_CONNECTIONS` / `KEEL_DB_CONNECT_TIMEOUT_MS` / `KEEL_DB_CAPABILITY`）；
 * `Config` 运行时落地后改为读 `application.yaml`，能力包与插件侧不受影响。
 */
internal class DatabaseSettings(
    val url: String,
    val username: String?,
    val password: String?,
    val maxConnections: Int,
    val connectTimeoutMs: Int,
    val capability: String?,
) {

    /** `mysql://` → `mysql`；没有 scheme 时为空串。 */
    val scheme: String get() = url.substringBefore("://", "").lowercase()

    companion object {

        /** 没有配置 `KEEL_DB_URL` 时返回 null。 */
        fun fromEnvironment(): DatabaseSettings? {
            val url = environment("KEEL_DB_URL") ?: return null
            return DatabaseSettings(
                url = url,
                username = environment("KEEL_DB_USER"),
                password = environment("KEEL_DB_PASSWORD"),
                maxConnections = environment("KEEL_DB_MAX_CONNECTIONS")?.toIntOrNull() ?: 8,
                connectTimeoutMs = environment("KEEL_DB_CONNECT_TIMEOUT_MS")?.toIntOrNull() ?: 3000,
                capability = environment("KEEL_DB_CAPABILITY"),
            )
        }

        private fun environment(name: String): String? =
            getenv(name)?.toKString()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
