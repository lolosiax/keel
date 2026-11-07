package moe.lolosia.keel.host.plugin

import kotlinx.cinterop.COpaquePointer

/**
 * 已加载的插件 dylib。
 *
 * 插件运行时不可卸载（K/N 不支持），所以只提供符号解析，不提供 close。
 */
internal expect class NativeLibrary(path: String) {

    /** 解析导出的 C 符号；没有导出时返回 null。 */
    fun symbol(name: String): COpaquePointer?
}
