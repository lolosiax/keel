package moe.lolosia.keel.api.service

import kotlinx.serialization.Serializable

/**
 * 插件启用时发给 Host 的服务声明。
 *
 * Host 只保存 `service id → plugin id`，不关心方法名与参数类型；
 * 契约由调用方与提供者共同依赖的 `-api` 工件保证。
 */
@Serializable
data class ServiceRegistration(
    val services: List<String> = emptyList(),
)
