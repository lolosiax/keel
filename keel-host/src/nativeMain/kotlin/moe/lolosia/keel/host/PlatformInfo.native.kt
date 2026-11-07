package moe.lolosia.keel.host

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

/** 运行平台描述，如 `Linux arm64`、`Windows x64`。 */
@OptIn(ExperimentalNativeApi::class)
internal fun platformDescription(): String {
    val os = Platform.osFamily.name.lowercase().replaceFirstChar { it.uppercase() }
    val arch = Platform.cpuArchitecture.name.lowercase()
    return "$os $arch"
}
