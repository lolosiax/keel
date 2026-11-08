package moe.lolosia.keel.host.auth

import kotlin.concurrent.Volatile
import kotlin.time.TimeSource
import moe.lolosia.keel.abi.c.KEEL_OK
import moe.lolosia.keel.abi.c.KEEL_SUBJECT_AUTHN
import moe.lolosia.keel.abi.c.KEEL_SUBJECT_AUTHZ
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.auth.*
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.web.Permissions
import moe.lolosia.keel.api.web.UserSnapshot
import moe.lolosia.keel.host.plugin.HostPump

/**
 * Host 侧的认证 / 授权基座。
 *
 * 实现不在 Host 里：`plugin.json` 声明 `provides: ["authn"]` / `["authz"]` 的插件
 * 各提供一个 SPI，Host 只记「SPI → plugin id」并经 [HostPump.request] 走事件请求。
 *
 * - 认证缓存 `token → 用户`，授权缓存 `userId → AuthzSnapshot`，TTL 见 [AuthSettings]；
 * - 缓存用不可变 Map 整体替换：写只发生在 Ktor 线程，读不加锁；
 * - 插件停用时撤销提供者并清掉它的缓存，受保护路由随即 fail-closed。
 */
internal object HostAuth {

    /** `plugin.json` 里声明认证实现的能力名。 */
    const val SPI_AUTHN = "authn"

    /** `plugin.json` 里声明授权实现的能力名。 */
    const val SPI_AUTHZ = "authz"

    private class CachedUser(val providerId: ULong, val user: UserSnapshot?, val expiresAt: Long)

    private class CachedAuthz(val providerId: ULong, val snapshot: AuthzSnapshot, val expiresAt: Long)

    private val settings = AuthSettings.fromEnvironment()
    private val clock = TimeSource.Monotonic.markNow()

    @Volatile
    private var authnProvider: ULong? = null

    @Volatile
    private var authzProvider: ULong? = null

    @Volatile
    private var authnCache: Map<String, CachedUser> = emptyMap()

    @Volatile
    private var authzCache: Map<String, CachedAuthz> = emptyMap()

    /** 是否已安装授权实现；没有时带 `@Permission` 的路由必须 503。 */
    fun hasAuthorizer(): Boolean = authzProvider != null

    fun isWhitelisted(path: String): Boolean = settings.isWhitelisted(path)

    /**
     * 启用前检查 manifest 的 `provides` 是否与已注册的提供者冲突。
     *
     * @return 冲突说明；可以注册时返回 null
     */
    fun conflict(pluginId: ULong, provides: List<String>): String? {
        val authn = authnProvider
        if (SPI_AUTHN in provides && authn != null && authn != pluginId) {
            return "认证实现已由 plugin_id=$authn 提供，不能再由 $pluginId 声明"
        }
        val authz = authzProvider
        if (SPI_AUTHZ in provides && authz != null && authz != pluginId) {
            return "授权实现已由 plugin_id=$authz 提供，不能再由 $pluginId 声明"
        }
        return null
    }

    /** 插件启动成功后登记它声明的 SPI。 */
    fun claim(pluginId: ULong, provides: List<String>) {
        if (SPI_AUTHN in provides) authnProvider = pluginId
        if (SPI_AUTHZ in provides) authzProvider = pluginId
    }

    /**
     * 插件停用 / 启动失败：撤销它的 SPI，并清掉归属它的缓存。
     *
     * 之后 `authenticate` 返回 null、受保护路由变为 503，直到新的提供者启用。
     */
    fun release(pluginId: ULong) {
        if (authnProvider == pluginId) authnProvider = null
        if (authzProvider == pluginId) authzProvider = null
        authnCache = authnCache.filterValues { it.providerId != pluginId }
        authzCache = authzCache.filterValues { it.providerId != pluginId }
    }

    /**
     * 解析 token；未安装认证实现时返回 null（由调用方按 401 处理）。
     *
     * @throws AuthUnavailableException 提供者超时、报错或应答无法解码
     */
    suspend fun authenticate(token: String): UserSnapshot? {
        val provider = authnProvider ?: return null
        val now = nowMs()
        authnCache[token]?.let { if (it.expiresAt > now) return it.user }

        val payload = ServiceWire.encode(AuthnRequest.serializer(), AuthnRequest(token))
        val reply = HostPump.request(provider, KEEL_SUBJECT_AUTHN.toULong(), payload, settings.timeoutMs)
            ?: throw AuthUnavailableException("认证实现未响应（plugin_id=$provider）")
        if (reply.status != KEEL_OK.toInt()) {
            throw AuthUnavailableException("认证实现返回 status=${reply.status}（plugin_id=$provider）")
        }
        val user = try {
            ServiceWire.decode(AuthnReply.serializer(), reply.payload ?: ByteArray(0)).user
        } catch (e: Throwable) {
            throw AuthUnavailableException("认证应答无法解码：${e.message}")
        }
        authnCache = authnCache + (token to CachedUser(provider, user, now + settings.cacheTtlMs))
        return user
    }

    /**
     * 把授权快照并进用户快照；没有授权实现时原样返回。
     *
     * 合并后插件的 `ctx.hasPermission` 才能本地判定，不需要每次回 Host。
     *
     * @throws AuthUnavailableException 授权实现超时、报错或应答无法解码
     */
    suspend fun enrich(user: UserSnapshot): UserSnapshot {
        if (authzProvider == null) return user
        val snapshot = snapshotOf(user.id)
        return user.copy(
            roles = snapshot.roles.ifEmpty { user.roles },
            rules = snapshot.rules,
            owner = snapshot.owner,
        )
    }

    /**
     * 判定用户是否拥有全部所需权限；`owner` 直接放行。
     *
     * @throws AuthUnavailableException 未安装授权实现或请求失败
     */
    suspend fun authorize(userId: String, permissions: List<String>): Boolean {
        if (permissions.isEmpty()) return true
        val snapshot = snapshotOf(userId)
        if (snapshot.owner) return true
        return permissions.all { Permissions.granted(snapshot.rules, it) }
    }

    /** 认证缓存失效；token / userId 都为空表示清空全部。 */
    fun invalidateAuthentication(token: String?, userId: String?) {
        authnCache = when {
            token != null -> authnCache - token
            userId != null -> authnCache.filterValues { it.user?.id != userId }
            else -> emptyMap()
        }
        Log.info(
            "认证缓存失效：token=${token ?: "*"} userId=${userId ?: "*"}",
            tag = "Auth",
        )
    }

    /** 授权缓存失效；userId 为空表示清空全部。 */
    fun invalidateAuthorization(userId: String?) {
        authzCache = if (userId == null) emptyMap() else authzCache - userId
        Log.info("授权缓存失效：userId=${userId ?: "*"}", tag = "Auth")
    }

    private suspend fun snapshotOf(userId: String): AuthzSnapshot {
        val provider = authzProvider ?: throw AuthUnavailableException("未安装授权实现")
        val now = nowMs()
        authzCache[userId]?.let { if (it.expiresAt > now) return it.snapshot }

        val payload = ServiceWire.encode(AuthzRequest.serializer(), AuthzRequest(userId))
        val reply = HostPump.request(provider, KEEL_SUBJECT_AUTHZ.toULong(), payload, settings.timeoutMs)
            ?: throw AuthUnavailableException("授权实现未响应（plugin_id=$provider）")
        if (reply.status != KEEL_OK.toInt()) {
            throw AuthUnavailableException("授权实现返回 status=${reply.status}（plugin_id=$provider）")
        }
        val snapshot = try {
            ServiceWire.decode(AuthzReply.serializer(), reply.payload ?: ByteArray(0)).snapshot
        } catch (e: Throwable) {
            throw AuthUnavailableException("授权应答无法解码：${e.message}")
        }
        authzCache = authzCache + (userId to CachedAuthz(provider, snapshot, now + settings.cacheTtlMs))
        return snapshot
    }

    private fun nowMs(): Long = clock.elapsedNow().inWholeMilliseconds
}
