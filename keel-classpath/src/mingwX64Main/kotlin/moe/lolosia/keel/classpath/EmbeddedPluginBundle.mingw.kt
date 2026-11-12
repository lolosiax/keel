package moe.lolosia.keel.classpath

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import platform.windows.FindResourceW
import platform.windows.LoadResource
import platform.windows.LockResource
import platform.windows.SizeofResource

/**
 * Windows：从 PE 资源段取 `keel-bundle.zip`。
 *
 * 由构建期 `moe.lolosia.keel.plugin` 生成：
 * ```
 * IDR_KEEL_BUNDLE  KEELBUNDLE  "keel-bundle.zip"
 * ```
 */
@Suppress("UNUSED_PARAMETER")
actual fun hasEmbeddedPluginBundle(executablePath: String): Boolean {
    val hRes = FindResourceW(null, RESOURCE_NAME, RESOURCE_TYPE) ?: return false
    return SizeofResource(null, hRes) > 0u
}

@Suppress("UNUSED_PARAMETER")
internal actual fun readEmbeddedPluginBundle(executablePath: String): ByteArray? {
    val hRes = FindResourceW(null, RESOURCE_NAME, RESOURCE_TYPE) ?: return null
    val size = SizeofResource(null, hRes)
    if (size == 0u) return null
    val hGlobal = LoadResource(null, hRes) ?: return null
    val pointer = LockResource(hGlobal) ?: return null
    return pointer.reinterpret<ByteVar>().readBytes(size.toInt())
}

private const val RESOURCE_NAME = "IDR_KEEL_BUNDLE"
private const val RESOURCE_TYPE = "KEELBUNDLE"
