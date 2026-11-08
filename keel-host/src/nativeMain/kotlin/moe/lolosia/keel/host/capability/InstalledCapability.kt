package moe.lolosia.keel.host.capability

import moe.lolosia.keel.host.plugin.KeelPackageManifest

/**
 * 已解包到缓存目录、尚未 `open` 的能力包。
 *
 * @property libraryPath 缓存目录里的动态库绝对路径
 */
internal class InstalledCapability(
    val manifest: KeelPackageManifest,
    val keelPath: String,
    val libraryPath: String,
)
