package moe.lolosia.keel.idea.run

import com.intellij.execution.Executor
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.LocatableConfigurationBase
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RuntimeConfigurationError
import com.intellij.execution.configurations.RuntimeConfigurationWarning
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.externalSystem.service.execution.ExternalSystemBeforeRunTask
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import org.jetbrains.plugins.gradle.execution.GradleBeforeRunTaskProvider
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.io.File

/**
 * 单个 Keel 插件工程的运行配置。
 *
 * 构建作为 IDEA 的 Gradle before-run task 执行，输出走 Build 面板；
 * 主 Run 控制台只启动 Host，不打印构建日志。
 */
class KeelRunConfiguration(project: Project, factory: ConfigurationFactory, name: String) :
    LocatableConfigurationBase<KeelRunConfigurationOptions>(project, factory, name) {

    private val keelOptions: KeelRunConfigurationOptions
        get() = super.getOptions() as KeelRunConfigurationOptions

    var pluginProjectDir: String?
        get() = keelOptions.pluginProjectDir
        set(value) {
            keelOptions.pluginProjectDir = value
        }

    var workDir: String?
        get() = keelOptions.workDir
        set(value) {
            keelOptions.workDir = value
        }

    var port: Int
        get() = keelOptions.port
        set(value) {
            keelOptions.port = value
        }

    var buildBeforeRun: Boolean
        get() = keelOptions.buildBeforeRun
        set(value) {
            keelOptions.buildBeforeRun = value
        }

    var programArguments: String
        get() = keelOptions.programArguments.orEmpty()
        set(value) {
            keelOptions.programArguments = value
        }

    override fun getConfigurationEditor(): SettingsEditor<out RunConfiguration> =
        KeelRunSettingsEditor(project)

    /**
     * 同步 IDEA 的 Gradle before-run task。
     *
     * 不能放在 `getBeforeRunTasks()` 里做：配置反序列化 / 保存时也会调用它，
     * `super.getBeforeRunTasks()` 可能返回只读列表，且会在读取状态期间修改配置。
     * 因此在项目打开、配置应用、执行前这些明确时机调用。
     */
    fun ensureBuildTask() {
        val tasks = super.getBeforeRunTasks().toMutableList()
        val ours = tasks.filterIsInstance<ExternalSystemBeforeRunTask>()
            .filter { it.taskExecutionSettings.taskNames.contains(GRADLE_TASK) }

        if (!buildBeforeRun) {
            if (ours.isEmpty()) return
            tasks.removeAll(ours)
            setBeforeRunTasks(tasks)
            return
        }

        val projectDirectory = pluginProjectDir?.takeIf { it.isNotBlank() } ?: return
        val existing = ours.firstOrNull()
        if (existing != null) {
            existing.taskExecutionSettings.externalProjectPath = projectDirectory
            return
        }

        val task = ExternalSystemBeforeRunTask(
            GradleBeforeRunTaskProvider.ID,
            GradleConstants.SYSTEM_ID,
        )
        task.taskExecutionSettings.externalProjectPath = projectDirectory
        task.taskExecutionSettings.taskNames = listOf(GRADLE_TASK)
        tasks.add(0, task)
        setBeforeRunTasks(tasks)
    }

    override fun getState(executor: Executor, environment: ExecutionEnvironment): RunProfileState {
        ensureBuildTask()
        return KeelRunProfileState(environment, this)
    }

    override fun checkConfiguration() {
        val dir = pluginProjectDir?.takeIf { it.isNotBlank() }?.let(::File)
        if (dir == null || !dir.isDirectory) {
            throw RuntimeConfigurationError("选择 Keel 插件工程目录")
        }
        val manifest = File(dir, "src/commonMain/resources/plugin.json")
        if (!manifest.isFile) {
            throw RuntimeConfigurationError("不是 Keel 插件工程：${manifest.absolutePath}")
        }
        val wrapper = KeelGradle.wrapper(dir)
        if (buildBeforeRun && !wrapper.isFile) {
            throw RuntimeConfigurationError("找不到 Gradle Wrapper：${wrapper.absolutePath}")
        }
        if (!buildBeforeRun && KeelGradle.findHost(resolvedWorkDir()) == null) {
            throw RuntimeConfigurationWarning(
                "运行目录里还没有 Host；关闭构建前请先执行 keelPrepareRun。"
            )
        }
    }

    /** 显式配置优先；否则用插件工程的 `build/keel-run`。 */
    fun resolvedWorkDir(): File {
        val explicit = workDir?.takeIf { it.isNotBlank() }
        if (explicit != null) return File(explicit)
        val dir = pluginProjectDir?.takeIf { it.isNotBlank() }?.let(::File)
            ?: throw RuntimeConfigurationError("选择 Keel 插件工程目录")
        return File(dir, "build/keel-run")
    }

    private companion object {
        const val GRADLE_TASK = "keelPrepareRun"
    }
}
