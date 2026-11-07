package moe.lolosia.keel.host

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
