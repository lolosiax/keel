package moe.lolosia.keel.gradle.plugin

import moe.lolosia.keel.gradle.classpath.KeelWindowsExtension
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.bundling.Zip

/**
 * 单 exe 产出配置。
 *
 * 默认关闭；开启后同一个插件工程会额外产出一个内嵌 `keel-bundle.zip` 的可执行文件。
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

    /** 取哪个构建类型的预编译 launcher：`DEBUG` / `RELEASE`。 */
    var buildType: String = "DEBUG"

    /** 本地 launcher 路径；为空时按 `keelVersion` 从 Maven 解析。 */
    var launcher: String? = null

    /** Windows 版本信息 / 图标；与 `keelClasspath.windows {}` 同形。 */
    val windows = KeelWindowsExtension()

    /** `File` / `FileCollection` / `Project` 输入；工程输入取 `keelPackage` 产物。 */
    internal val inputs: ConfigurableFileCollection = project.objects.fileCollection()

    private val coordinates = mutableListOf<String>()

    fun include(input: Any) {
        when (input) {
            is Project -> includeProject(input)
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

    internal fun coordinates(): List<String> = coordinates.toList()

    private fun includeProject(other: Project) {
        val task = try {
            other.tasks.named("keelPackage", Zip::class.java)
        } catch (e: Exception) {
            throw GradleException(
                "keelPlugin.executable.include(project) 需要目标工程应用 moe.lolosia.keel.plugin：" +
                    "${other.path} 没有 keelPackage 任务",
                e,
            )
        }
        inputs.from(task.flatMap { it.archiveFile })
    }
}
