package moe.lolosia.keel.api.service

/**
 * 目标模块不可用：未启用、已崩溃、或没有注册该服务。
 *
 * 调用方可以重试；重试仍失败时应降级，而不是让整个请求失败。
 */
class ServiceUnavailableException(
    val serviceId: String,
    message: String = "service unavailable: $serviceId",
) : RuntimeException(message)
