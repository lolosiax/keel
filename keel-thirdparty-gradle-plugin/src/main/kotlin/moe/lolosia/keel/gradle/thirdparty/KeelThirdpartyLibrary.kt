package moe.lolosia.keel.gradle.thirdparty

/**
 * 一个被消费的第三方原生库。
 *
 * 默认从 Maven 坐标 `<group>:<name>-<target>:<version>@zip` 解析；
 * 设置 [dir] 后改为读取本地包目录（离线开发 / 自带产物）。
 */
open class KeelThirdpartyLibrary(val name: String) {

    /** 本地产物目录；为空时按 Maven 坐标解析。 */
    var dir: String? = null

    /** 工件版本；为空时取 [KeelThirdpartyExtension.version] / 工程版本。 */
    var version: String? = null

    /** cinterop 生成的 Kotlin 包名；默认 `moe.lolosia.keel.thirdparty.<name>`。 */
    var packageName: String = "moe.lolosia.keel.thirdparty." + name.replace('-', '_').replace('.', '_')

    /** 包内 `.def` 文件名；默认 `<name>.def`。 */
    var defFile: String = "$name.def"

    /**
     * 覆盖 `.def` 里的 `headerFilter`；为空则原样使用包内声明。
     *
     * 设置后构建期会生成一份只追加该行的派生 def，包内文件不被修改。
     */
    var headerFilter: String? = null

    /** 只参与这些 native 目标；为空则全部目标。 */
    val targets: MutableList<String> = mutableListOf()

    /** 追加给最终链接的参数（如 `-lpthread`）；库本身的 `-l` 由包内 def 声明。 */
    val linkerOpts: MutableList<String> = mutableListOf()

    fun target(name: String) {
        targets += name
    }

    fun targets(vararg names: String) {
        targets += names
    }

    fun linkerOpts(vararg values: String) {
        linkerOpts += values
    }
}
