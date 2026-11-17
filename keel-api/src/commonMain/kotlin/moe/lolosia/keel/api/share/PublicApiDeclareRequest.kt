package moe.lolosia.keel.api.share

import kotlinx.serialization.Serializable

/**
 * 声明方在启用时上报的类型集合；Host 据此决定调用路由到哪个插件。
 */
@Serializable
data class PublicApiDeclareRequest(
    val types: List<PublicApiType> = emptyList(),
)
