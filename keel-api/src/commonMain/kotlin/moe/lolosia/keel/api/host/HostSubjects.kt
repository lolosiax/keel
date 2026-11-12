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

    /** 请求：读取配置；载荷 / 应答见 `ConfigReadRequest` / `ConfigReadReply`。 */
    const val CONFIG_READ: Long = 0x1004L

    /** 请求：写入配置（合并后落盘）；载荷 / 应答见 `ConfigWriteRequest` / `ConfigDocReply`。 */
    const val CONFIG_WRITE: Long = 0x1005L

    /** 请求：插件命名空间存储；载荷 / 应答见 `StorageRequest` / `StorageReply`。 */
    const val STORAGE: Long = 0x1006L

    /** 请求：读 `.keel` 资源；载荷 / 应答见 [ClasspathRequest] / [ClasspathReply]。 */
    const val CLASSPATH: Long = 0x1007L

    /** 请求：本插件的可写缓存目录；载荷为空，应答见 [CacheDirReply]。 */
    const val CACHE_DIR: Long = 0x1008L

    /** 请求：把包内资源物化到缓存目录；载荷 / 应答见 [MaterializeRequest] / [MaterializeReply]。 */
    const val MATERIALIZE: Long = 0x1009L

    /** 请求：订阅日志；载荷 / 应答见 [LogSubscribeRequest] / [LogSubscribeReply]。 */
    const val LOG_SUBSCRIBE: Long = 0x100AL

    /** 请求：拉取日志；载荷 / 应答见 [LogFetchRequest] / `LogPage`。 */
    const val LOG_FETCH: Long = 0x100BL

    /** 通知：退订；载荷见 [LogUnsubscribeRequest]。 */
    const val LOG_UNSUBSCRIBE: Long = 0x100CL

    /** 请求：取单独一条日志；载荷 / 应答见 [LogGetRequest] / [LogGetReply]。 */
    const val LOG_GET: Long = 0x100DL
}
