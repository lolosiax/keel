package moe.lolosia.keel.classpath

import kotlinx.cinterop.COpaquePointer

/** 嵌入资源的映像：指向二进制内的数据 + 字节数。 */
class EmbeddedClasspathImage(
    val pointer: COpaquePointer,
    val size: Int,
)
