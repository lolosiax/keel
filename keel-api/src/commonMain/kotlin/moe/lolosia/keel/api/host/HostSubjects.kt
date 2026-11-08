package moe.lolosia.keel.api.host

/**
 * 插件 → Host 的业务 subject。
 *
 * 与 ABI 头文件里的协议 subject（`KEEL_SUBJECT_*`，< 0x1000）分开编号；
 * Host 只按这里声明的值分发，新增 subject 时两边同时改。
 */
object HostSubjects {

    /** 请求：Host 聚合后的权限节点目录，应答是 CBOR 的 [PermissionCatalogReply]。 */
    const val PERMISSION_CATALOG: Long = 0x1001L

    /** 通知：认证缓存失效，载荷是 CBOR 的 [InvalidateRequest]。 */
    const val AUTH_INVALIDATE: Long = 0x1002L

    /** 通知：授权缓存失效，载荷是 CBOR 的 [InvalidateRequest]。 */
    const val AUTHZ_INVALIDATE: Long = 0x1003L
}
