package moe.lolosia.keel.idea.debug

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import moe.lolosia.keel.idea.run.KeelRunConfiguration
import moe.lolosia.keel.idea.run.KeelRunProfileState
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 调试会话的进程状态。
 *
 * 命令行与 Run 完全一致，只多一个 `KEEL_DEBUG_PORT` 环境变量。
 * **IDE 负责监听**：Host exe 与每个插件 dylib 是独立的运行时 image，
 * 各自带一份 agent，启动时都主动连到同一个端口，一个 image 一条通道。
 */
class KeelDebugProfileState(
    environment: ExecutionEnvironment,
    configuration: KeelRunConfiguration,
) : KeelRunProfileState(environment, configuration) {

    /** 监听 socket；[startProcess] 成功返回后非空。 */
    var server: ServerSocket? = null
        private set

    override fun startProcess(): ProcessHandler {
        // 先建监听再起进程：Host 启动时就会来连，晚建会白等重试
        val listener = ServerSocket()
        listener.reuseAddress = true
        listener.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), MAX_PENDING)
        server = listener
        debugPort = listener.localPort

        return try {
            super.startProcess()
        } catch (e: Throwable) {
            runCatching { listener.close() }
            server = null
            throw e
        }
    }

    private companion object {
        /** Host + 若干插件 image；backlog 给宽一点，连接是瞬时的。 */
        const val MAX_PENDING = 16
    }
}
