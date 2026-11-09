package moe.lolosia.keel.idea.run

import java.io.File

/**
 * Gradle Wrapper 与运行产物的定位。
 *
 * IDE 插件不依赖 IDEA 的 Gradle 同步 API；直接调用工程自带的 wrapper，
 * 这样独立插件工程与 composite 根工程行为一致。
 */
object KeelGradle {

    fun wrapper(projectDir: File): File {
        val name = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "gradlew.bat"
        } else {
            "gradlew"
        }
        var current: File? = projectDir
        while (current != null) {
            val candidate = File(current, name)
            if (candidate.isFile) return candidate
            current = current.parentFile
        }
        return File(projectDir, name)
    }

    fun findHost(workDir: File): File? {
        val hostDir = File(workDir, "host")
        if (!hostDir.isDirectory) return null
        return hostDir.walkTopDown()
            .firstOrNull { it.isFile && it.nameWithoutExtension == "keel-host" }
    }
}
