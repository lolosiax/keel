package moe.lolosia.keel.plugins.basic.biz.db

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.lolosia.keel.plugins.basic.biz.newIdentifier
import moe.lolosia.keel.plugins.basic.biz.nowDisplay
import moe.lolosia.keel.plugins.basic.biz.nowEpochMs

/**
 * 会话仓库：token 就是会话 id（不是 JWT），过期时间存在 `sys_sessions.data` 的 JSON 里。
 *
 * 只存最少信息：Host 的认证缓存按 token 命中，插件每个请求只做一次主键查询。
 */
internal object AccountRepository {

    @Serializable
    private class SessionData(val expiresAt: Long = 0L)

    private val json = Json { ignoreUnknownKeys = true }

    fun create(userId: String, ttlSeconds: Long): String {
        val token = newIdentifier()
        val now = nowDisplay()
        val data = json.encodeToString(
            SessionData.serializer(),
            SessionData(nowEpochMs() + ttlSeconds.coerceAtLeast(60L) * 1000L),
        )
        execute(
            "INSERT INTO sys_sessions (id, user_id, data, created_at, updated_at, deleted) " +
                "VALUES (?, ?, ?, ?, ?, 0)",
            listOf(textParam(token), textParam(userId), textParam(data), textParam(now), textParam(now)),
        )
        return token
    }

    /** token → userId；不存在 / 已吊销 / 已过期返回 null（过期顺手吊销）。 */
    fun userIdOf(token: String): String? {
        val row = selectOne(
            "SELECT user_id, data FROM sys_sessions WHERE id = ? AND deleted = 0",
            listOf(textParam(token)),
        ) { it.text(0) to it.text(1) } ?: return null

        val userId = row.first?.takeIf { it.isNotBlank() } ?: return null
        val data = row.second?.let {
            runCatching { json.decodeFromString(SessionData.serializer(), it) }.getOrNull()
        } ?: return null
        if (data.expiresAt <= nowEpochMs()) {
            revoke(token)
            return null
        }
        return userId
    }

    fun revoke(token: String) {
        execute(
            "UPDATE sys_sessions SET deleted = 1, updated_at = ? WHERE id = ? AND deleted = 0",
            listOf(textParam(nowDisplay()), textParam(token)),
        )
    }

    fun revokeByUser(userId: String) {
        execute(
            "UPDATE sys_sessions SET deleted = 1, updated_at = ? WHERE user_id = ? AND deleted = 0",
            listOf(textParam(nowDisplay()), textParam(userId)),
        )
    }

    /** 当前未吊销的会话数（仪表盘顶部胶囊用；不逐个校验过期时间）。 */
    fun activeCount(): Long = selectOne(
        "SELECT COUNT(*) FROM sys_sessions WHERE deleted = 0 AND user_id IS NOT NULL",
    ) { it.longOrNull(0) ?: 0L } ?: 0L
}
