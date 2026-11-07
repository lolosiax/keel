package moe.lolosia.keel.gradle.plugin

/**
 * `.keel` 打包扩展。
 *
 * 默认把 `src/commonMain/resources` 作为资源，`plugin.json` 放在包根；
 * `targets` 为空时按当前宿主选择（Windows → mingwX64，Linux ARM → linuxArm64）。
 */
open class KeelPluginExtension {

    var enabled: Boolean = true

    /** 包根 manifest 的源文件。 */
    var manifest: String = "src/commonMain/resources/plugin.json"

    /** 取哪个构建类型的 sharedLib：`DEBUG` / `RELEASE`。 */
    var buildType: String = "DEBUG"

    /** 输出文件名；默认 `<project>-<version>.keel`。 */
    var packageName: String? = null

    /** 是否自动给 `commonMainImplementation` 加 `keel-classpath-full`。 */
    var autoAddClasspathFull: Boolean = true

    /** 自动添加的版本；默认取 project.version。 */
    var keelVersion: String? = null

    /** 参与打包的目标；为空则取宿主目标。 */
    val targets: MutableList<String> = mutableListOf()

    /** 额外资源目录；统一放进包内 `resources/`。 */
    val resources: MutableList<String> = mutableListOf()

    fun target(name: String) {
        targets += name
    }

    fun targets(vararg names: String) {
        targets += names
    }

    fun resources(vararg paths: String) {
        resources += paths
    }
}
