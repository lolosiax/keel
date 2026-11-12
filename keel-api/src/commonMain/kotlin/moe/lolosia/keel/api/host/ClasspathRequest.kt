package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable

/**
 * 跨运行时读取 `.keel` 资源的请求（`HostSubjects.CLASSPATH`，CBOR）。
 *
 * @property op 读 / 存在性 / 列表
 * @property path 资源路径，以对方资源根为基准
 * @property pluginId 目标插件 id；为空表示调用插件自己的 `.keel`。
 *   Host 自身的资源用 [ClasspathNames.HOST]。
 */
@Serializable
data class ClasspathRequest(
    val op: ClasspathOp,
    val path: String = "",
    val pluginId: String? = null,
)
