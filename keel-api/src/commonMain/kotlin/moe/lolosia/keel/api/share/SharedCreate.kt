package moe.lolosia.keel.api.share

import kotlinx.serialization.Serializable

/** 创建者 → Host：请求按 [typeId] 构造一个共享对象。 */
@Serializable
data class SharedCreateRequest(
    val typeId: String,
)

/** Host → 提供者：按 [typeId] 构造对象，并接管 Host 分配的 [handle]。 */
@Serializable
data class SharedCreateCommand(
    val typeId: String,
    val handle: Long,
)

/**
 * 提供者 → Host 的构造回执。
 *
 * @property handle 构造成功的对象句柄
 * @property interfaces 实例额外导出的类型
 * @property overrides 实例实际覆盖的方法线名
 * @property initialization 是否声明了 `initialization`；为 true 时 Host 保持 Creating，
 *   等创建者初始化成功后才转为 Open
 */
@Serializable
data class SharedCreateReply(
    val handle: Long,
    val interfaces: List<String> = emptyList(),
    val overrides: List<String> = emptyList(),
    val initialization: Boolean = false,
)
