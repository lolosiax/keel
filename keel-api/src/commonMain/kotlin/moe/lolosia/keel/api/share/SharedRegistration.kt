package moe.lolosia.keel.api.share

import kotlinx.serialization.Serializable

/**
 * 对象首次跨界时发给 Host 的登记请求。
 *
 * @property typeId 主要导出类型，也是单例查找用的键
 * @property interfaces 额外导出的类型集合
 * @property overrides 实例方实际覆盖的方法线名；声明方的影子据此决定本地执行还是转发
 * @property singleton 是否登记为该类型的单例，供 `getInstance` 查找
 */
@Serializable
data class SharedRegistration(
    val typeId: String,
    val interfaces: List<String> = emptyList(),
    val overrides: List<String> = emptyList(),
    val singleton: Boolean = false,
)
