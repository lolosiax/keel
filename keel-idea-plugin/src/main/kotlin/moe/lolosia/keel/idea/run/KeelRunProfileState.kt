package moe.lolosia.keel.idea.run

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.ParametersList
import com.intellij.execution.process.ColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import java.io.File

/**
 * 直接启动 Host。
 *
 * 构建由 [KeelRunConfiguration.ensureBuildTask] 维护的 Gradle before-run task 负责，
 * 输出在 IDEA 的 Build 面板；这里只把 Host 进程接到 Run 控制台。
 *
 * [debugPort] 非空表示这是调试会话：只通过环境变量 `KEEL_DEBUG_PORT` 告诉 Host，
 * 不新增命令行参数，避免 Host 现有的参数解析报「无法识别的参数」。
 */
open class KeelRunProfileState(
    environment: ExecutionEnvironment,
    private val configuration: KeelRunConfiguration,
    protected var debugPort: Int? = null,
) : CommandLineState(environment) {

    public override fun startProcess(): ProcessHandler {
        val workDirectory = configuration.resolvedWorkDir()
        val host = KeelGradle.findHost(workDirectory)
            ?: throw ExecutionException(
                "找不到 Host：${File(workDirectory, "host").absolutePath}。请先执行 keelPrepareRun。"
            )
        val plugins = File(workDirectory, "plugins")
        val commandLine = GeneralCommandLine(host.absolutePath)
            .withWorkDirectory(workDirectory)
            .withParameters("--plugins", plugins.absolutePath, "--http=${configuration.port}")
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)

        if (System.getenv("NO_COLOR") == null) {
            commandLine.withEnvironment("FORCE_COLOR", "1")
        }
        debugPort?.let { commandLine.withEnvironment("KEEL_DEBUG_PORT", it.toString()) }
        ParametersList.parse(configuration.programArguments).forEach { commandLine.addParameter(it) }

        return ColoredProcessHandler(commandLine).apply {
            setShouldDestroyProcessRecursively(true)
        }
    }
}
