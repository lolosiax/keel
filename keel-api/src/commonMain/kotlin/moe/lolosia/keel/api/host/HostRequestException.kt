package moe.lolosia.keel.api.host

/**
 * 反向请求在本地失败：超时、插件已停用，或 Host 没有对应处理器。
 *
 * Host 处理器内部抛出的异常会以 `KeelRemoteException` 重建，不走这里。
 */
class HostRequestException(
    val subject: Long,
    message: String,
) : RuntimeException(message)
