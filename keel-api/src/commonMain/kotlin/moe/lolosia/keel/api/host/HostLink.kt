package moe.lolosia.keel.api.host

import kotlinx.serialization.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.web.PermissionNode

/**
 * 插件 → Host 的通道。
 *
 * 请求和通知都走事件泵：Host 在**它自己的线程**上处理，插件只等待 correlation 配对的回包，
 * 不跨运行时传 Kotlin 对象或 continuation。
 */
interface HostLink {

    /**
     * 单向通知：at-most-once，不等待 Host 处理结果。
     *
     * @param subject [HostSubjects] 里的业务 subject
     */
    fun post(subject: Long, payload: ByteArray)

    /**
     * 请求 Host 并等待应答；只能在 Worker 线程调用。
     *
     * 等待期间会继续分发其它事件（与 `ServiceCaller.callBlocking` 同一语义），
     * 默认超时 3s。
     *
     * @throws HostRequestException 超时、插件已停用或 Host 没有对应处理器
     */
    fun request(subject: Long, payload: ByteArray): ByteArray

    /** 取 Host 聚合后的权限节点目录；阻塞等待应答。 */
    fun permissionCatalog(): List<PermissionNode> =
        ServiceWire.decode(
            serializer<PermissionCatalogReply>(),
            request(HostSubjects.PERMISSION_CATALOG, ByteArray(0)),
        ).nodes

    /** 让 Host 清掉认证缓存；`token` / `userId` 为空表示全部。 */
    fun invalidateAuthentication(token: String? = null, userId: String? = null) {
        post(
            HostSubjects.AUTH_INVALIDATE,
            ServiceWire.encode(
                serializer<InvalidateRequest>(),
                InvalidateRequest(userId = userId, token = token),
            ),
        )
    }

    /** 让 Host 清掉授权缓存；`userId` 为空表示全部。 */
    fun invalidateAuthorization(userId: String? = null) {
        post(
            HostSubjects.AUTHZ_INVALIDATE,
            ServiceWire.encode(
                serializer<InvalidateRequest>(),
                InvalidateRequest(userId = userId),
            ),
        )
    }
}
