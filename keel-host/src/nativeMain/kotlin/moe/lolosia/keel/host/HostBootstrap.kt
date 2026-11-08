package moe.lolosia.keel.host

import kotlin.time.TimeSource
import moe.lolosia.keel.abi.c.KEEL_ABI_VERSION
import moe.lolosia.keel.abi.c.keel_install_shutdown_hook
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.host.build.BuildInfo
import moe.lolosia.keel.host.plugin.currentTargetName

/**
 * Host 启动信息：版本、工具链、可执行文件与工作目录。
 *
 * 对应 Paper 的 bootstrap 行；插件数量与单个插件的状态仍由 Loader 输出。
 */
internal object HostBootstrap {

    /** 对象在打印横幅时初始化；运行期就绪后用它计算启动耗时。 */
    private val startedAt = TimeSource.Monotonic.markNow()

    private var startupLogged = false

    /**
     * 打印一次启动完成。
     *
     * 由 `HostRuntime` 在插件启动、HTTP 连接绑定与控制台就绪之后调用。
     */
    fun logStartupComplete() {
        if (startupLogged) return
        startupLogged = true
        Log.info("Keel 在 ${elapsedText()} 内完成启动", tag = "Server")
    }

    /** 不足一秒按毫秒取整，否则保留两位小数秒。 */
    private fun elapsedText(): String {
        val millis = startedAt.elapsedNow().inWholeMilliseconds
        if (millis < 1000) return "${millis}ms"
        val fraction = (millis % 1000) / 10
        return "${millis / 1000}.${if (fraction < 10) "0$fraction" else "$fraction"}s"
    }

    /**
     * 尽早安装退出钩子：插件扫描/启动阶段按下 Ctrl+C 也只会置位，
     * 等 HostRuntime 进入主循环后立刻走优雅停机。
     */
    fun installShutdownHook() {
        keel_install_shutdown_hook()
    }

    fun printStartupInfo() {
        printBanner(BuildInfo.BUILD_ID, currentTargetName)
        Log.info(
            "running Kotlin ${BuildInfo.KOTLIN_VERSION} (keel ${BuildInfo.BUILD_ID}) on ${platformDescription()}",
            tag = "Bootstrap",
        )
        Log.info(
            "loading keel ${BuildInfo.BUILD_ID} (${BuildInfo.COMMIT_TIME}) for $currentTargetName, abi $KEEL_ABI_VERSION",
            tag = "Bootstrap",
        )
        Log.info("executable: ${executablePath()}", tag = "Bootstrap")
        Log.info("working dir: ${currentWorkingDirectory()}", tag = "Bootstrap")
    }
}

/** 由 `main` 在看门狗启动前调用，保证横幅排在所有日志之前。 */
internal actual fun printStartupBanner() = HostBootstrap.printStartupInfo()
