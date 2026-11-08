package moe.lolosia.keel.host.auth

/**
 * 认证 / 授权实现不可用：未安装、已停用、超时或应答无法解码。
 *
 * Host 对受保护路由统一映射成 503；不能降级成 401 / 403，
 * 否则前端会误以为「token 失效」而不是「服务暂时不可用」。
 */
internal class AuthUnavailableException(message: String) : IllegalStateException(message)
