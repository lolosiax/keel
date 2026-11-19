package moe.lolosia.keel.gradle.plugin

import moe.lolosia.keel.gradle.classpath.KeelWindowsExtension
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection

/**
 * 单 exe 产出配置。
 *
 * 默认关闭；开启后同一个插件工程会额外产出一个内嵌 `keel-bundle` 的可执行文件。
 * 本工程自己的 `.keel` 会自动进入 bundle，[include] 用于追加依赖插件 / 能力包。
 */
open class KeelExecutableExtension(private val project: Project) {

    /** 是否产出单 exe；默认关闭。 */
    var enabled: Boolean = false

    /**
     * 构建控制台应用还是桌面应用。
     *
     * `true`（默认）写 PE 控制台子系统；`false` 写 GUI 子系统，双击不弹窗，
     * 运行期用 `--console` 或 `KEEL_CONSOLE=1` 仍可附着 / 新建控制台。
     * 只影响 Windows 单 exe；`.keel` 与 Linux 目标不受影响。
     */
    var console: Boolean = true

    /** 输出文件名；默认工程名。Windows 自动补 `.exe`。 */
    var name: String = project.name

    /**
     * 单 exe 启动时的默认目录模式。
     *
     * - [KeelDataMode.PROGRAM]（默认）：数据写在可执行文件所在目录，适合 Web 式部署；
     * - [KeelDataMode.USERDATA]：数据写在按 app id 隔离的用户目录，适合桌面应用
     *   （Windows `%LOCALAPPDATA%\<name>`，Linux `$XDG_DATA_HOME/<name>`）。
     *
     * 只影响单 exe 的内嵌运行期默认值；运行期仍可用 `KEEL_DATA_HOME` /
     * `KEEL_CACHE_HOME` 覆盖。`.keel` 与 `--plugins` 模式不受影响。
     *
     * ```kotlin
     * import moe.lolosia.keel.gradle.plugin.KeelDataMode
     *
     * keel {
     *     plugin {
     *         executable { dataMode = KeelDataMode.USERDATA }
     *     }
     * }
     * ```
     */
    var dataMode: KeelDataMode = KeelDataMode.PROGRAM

    /**
     * 用户目录下的应用 id，决定 `%LOCALAPPDATA%\<appId>`（Linux `$XDG_DATA_HOME/<appId>`）。
     *
     * 默认取工程名，与产物文件名解耦：exe 改名 / 多语言产物 / 带版本号的产物名都不会
     * 改变数据目录。不能为空，不能包含 `\ / : * ? " < > |`，建议只用字母、数字、`-`、`_`、`.`。
     */
    var appId: String = project.name

    /**
     * 取哪个构建类型的产物：`DEBUG` / `RELEASE`。
     *
     * `RELEASE` 时本工程用 `keelPackageRelease`（release sharedLib、已 strip），
     * launcher 也解析 `keel-launcher-<target>`；`DEBUG` 保持原来的调试语义。
     * 可用 `-Pkeel.buildType=RELEASE` 覆盖。
     */
    var buildType: String = "DEBUG"

    /**
     * bundle 容器压缩方式：`zstd`（默认）或 `deflate`。
     *
     * 只影响单 exe 内嵌的 bundle；`.keel` 自身的 zip 压缩不变。
     * `zstd` 需要运行期 Host 带 `keel-abi` 的解压器（0.1.0 起）。
     */
    var compression: String = "zstd"

    /** zstd 压缩级别；默认 19。 */
    var compressionLevel: Int = 19

    /** 本地 launcher 路径；为空时按 `keelVersion` 从 Maven 解析。 */
    var launcher: String? = null

    /** Windows 版本信息 / 图标；与 `keel { classpath { windows {} } }` 同形。 */
    val windows = KeelWindowsExtension()

    /** `File` / `FileCollection` 输入；工程输入取 `keelPackage` / `keelPackageRelease`。 */
    val inputs: ConfigurableFileCollection = project.objects.fileCollection()

    /** `include(project(...))` 的工程输入；等 afterEvaluate 确定 buildType 后再挑任务。 */
    val projectInputs: MutableList<Project> = mutableListOf()

    private val coordinates = mutableListOf<String>()

    fun include(input: Any) {
        when (input) {
            is Project -> projectInputs += input
            is CharSequence -> coordinates += input.toString()
            else -> inputs.from(input)
        }
    }

    fun include(vararg inputs: Any) {
        inputs.forEach { include(it) }
    }

    fun windows(action: Action<KeelWindowsExtension>) {
        action.execute(windows)
    }

    fun coordinates(): List<String> = coordinates.toList()

    fun isRelease(): Boolean = !buildType.equals("DEBUG", ignoreCase = true)

    /** 是否按 USERDATA 模式出包。 */
    fun usesUserData(): Boolean = dataMode == KeelDataMode.USERDATA

    /** 本工程与 `include(Project)` 使用的打包任务：release 走 `keelPackageRelease`。 */
    fun packageTaskName(): String = if (isRelease()) "keelPackageRelease" else "keelPackage"
}
