package moe.lolosia.keel.gradle.plugin

import java.util.Locale

/**
 * 当前宿主对应的 Kotlin/Native 目标名。
 *
 * 插件打包与 `keelRun` 都按这个值选 `keel-host-<target>`，保证两边一致。
 */
internal fun hostTargetName(): String {
    val os = System.getProperty("os.name").lowercase(Locale.ROOT)
    val arch = System.getProperty("os.arch").lowercase(Locale.ROOT)
    return when {
        os.contains("win") -> "mingwX64"
        arch.contains("aarch64") || arch.contains("arm64") -> "linuxArm64"
        else -> "linuxX64"
    }
}
