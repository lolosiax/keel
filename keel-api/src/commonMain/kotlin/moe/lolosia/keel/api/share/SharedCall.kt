package moe.lolosia.keel.api.share

import kotlinx.serialization.Serializable

/**
 * 一次共享对象方法调用的信封。
 *
 * @property typeId 调用点的静态类型；Host 用它选路由并校验对象是否导出该类型
 * @property handle 对象句柄
 * @property method 方法线名；`base.` 前缀表示只跑声明方的基类体
 * @property args 参数的 CBOR 字节
 * @property owner true 表示直接发给对象所有者（影子转发覆盖方法时使用）
 * @property overrides 实例方实际覆盖的方法线名；由 Host 在转发时填入
 */
@Serializable
data class SharedCall(
    val typeId: String,
    val handle: Long,
    val method: String,
    val args: ByteArray = ByteArray(0),
    val owner: Boolean = false,
    val overrides: List<String> = emptyList(),
)
