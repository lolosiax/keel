package moe.lolosia.keel.api.share

import kotlinx.serialization.Serializable

/**
 * 声明方上报给 Host 的类型描述。
 *
 * @property typeId 全局唯一的类型 id，带插件前缀
 * @property hasBase 是否是带基类体的类；为 true 时调用先路由到声明方执行影子
 */
@Serializable
data class PublicApiType(
    val typeId: String,
    val hasBase: Boolean = false,
)
