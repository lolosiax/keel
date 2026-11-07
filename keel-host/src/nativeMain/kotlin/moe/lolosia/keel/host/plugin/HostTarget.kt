package moe.lolosia.keel.host.plugin

import kotlin.experimental.ExperimentalNativeApi

/**
 * 当前 Host 二进制的 K/N 目标名，用于在 `.keel` 的 `lib` 映射里选库。
 *
 * 名字必须与 Gradle 的 target 名一致（`mingwX64` / `linuxArm64` / `linuxX64`），
 * 否则 `.keel` 里按目标分目录的库会选不到。
 */
@OptIn(ExperimentalNativeApi::class)
internal val currentTargetName: String = when {
    Platform.osFamily == OsFamily.WINDOWS && Platform.cpuArchitecture == CpuArchitecture.X64 ->
        "mingwX64"

    Platform.osFamily == OsFamily.LINUX && Platform.cpuArchitecture == CpuArchitecture.ARM64 ->
        "linuxArm64"

    Platform.osFamily == OsFamily.LINUX && Platform.cpuArchitecture == CpuArchitecture.X64 ->
        "linuxX64"

    else -> error(
        "不支持的平台：os=${Platform.osFamily} arch=${Platform.cpuArchitecture}"
    )
}
