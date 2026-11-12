package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable

/** 跨运行时读取 `.keel` 资源的操作（`HostSubjects.CLASSPATH`）。 */
@Serializable
enum class ClasspathOp {
    READ,
    EXISTS,
    LIST,
}
