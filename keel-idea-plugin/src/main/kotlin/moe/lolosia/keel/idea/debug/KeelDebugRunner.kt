package moe.lolosia.keel.idea.debug

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import moe.lolosia.keel.idea.run.KeelRunConfiguration
import java.io.File

/**
 * Debug executor 的入口。
 *
 * 虫子按钮能不能点，取决于有没有一个 ProgramRunner 对它返回 true；
 * 这里只接管 Keel 自己的运行配置，不影响其它运行配置。
 */
class KeelDebugRunner : ProgramRunner<RunnerSettings> {

    override fun getRunnerId(): String = "KeelDebugRunner"

    override fun canRun(executorId: String, profile: RunProfile): Boolean =
        executorId == DefaultDebugExecutor.EXECUTOR_ID && profile is KeelRunConfiguration

    override fun execute(environment: ExecutionEnvironment) {
        val configuration = environment.runProfile as? KeelRunConfiguration
            ?: throw ExecutionException("KeelDebugRunner 只能用于 Keel 运行配置")
        val state = configuration.getState(environment.executor, environment) as? KeelDebugProfileState
            ?: throw ExecutionException("没有拿到 Keel 调试状态")
        val handler = state.startProcess()
        val server = state.server ?: throw ExecutionException("调试监听端口没有建立")
        val debugDir = File(configuration.resolvedWorkDir(), "debug")

        XDebuggerManager.getInstance(environment.project).startSessionAndShowTab(
            configuration.name,
            object : XDebugProcessStarter() {
                override fun start(session: XDebugSession) =
                    KeelDebugProcess(session, handler, server, debugDir)
            },
            environment,
        )
    }
}
