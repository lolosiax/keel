package moe.lolosia.keel.gradle.abi

/**
 * `moe.lolosia.keel.abi` 的配置项。
 *
 * 默认从同仓库的 `keel-abi` 构建产物取包；发布后改成 Maven 依赖即可，业务构建不用改。
 */
open class KeelAbiExtension {

    var enabled: Boolean = true

    /** keel-abi 的版本，默认取 `project.version`。 */
    var version: String? = null

    /** 直接指定 `keel-abi-<version>.zip`；留空时按同仓库约定路径查找。 */
    var zipFile: String? = null

    /** 参与注入的目标；为空则注入全部 native 目标。 */
    val targets: MutableList<String> = mutableListOf()

    fun target(name: String) {
        targets += name
    }

    fun targets(vararg names: String) {
        targets += names
    }
}
