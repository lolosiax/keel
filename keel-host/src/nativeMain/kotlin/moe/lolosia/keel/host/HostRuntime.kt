package moe.lolosia.keel.host

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import moe.lolosia.keel.abi.c.keel_shutdown_requested
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.host.console.ConsolePrompt
import moe.lolosia.keel.host.console.HostConsole
import moe.lolosia.keel.host.plugin.HostPlugins
import moe.lolosia.keel.host.plugin.HostPump

/**
 * Host 运行期：HTTP 入口、控制台、事件循环与优雅停机。
 *
 * 插件扫描、加载与启动由 `Plugins.kt` 负责；这里只接管「跑起来之后」的事情，
 * 每轮循环先取 stdin 的命令（非阻塞），再阻塞式消费一次事件泵。
 */
internal object HostRuntime {

    fun run(serveMillis: Long?, httpPort: Int?) {
        // 中断标志由 HostBootstrap 在启动时就装好；这里只轮询
        // 有 HTTP 但没有显式 serve 选项时常驻，否则服务一启动就退出
        val effectiveServe = serveMillis ?: if (httpPort != null) 0L else null
        httpPort?.let { HostHttp.start(it, HostPlugins.enabled().firstOrNull()?.pluginId) }

        if (effectiveServe != null) HostConsole.start()
        // 插件、HTTP 与控制台都已就绪；HTTP 的连接绑定在 HostHttp.start 里已经等到
        HostBootstrap.logStartupComplete()

        if (effectiveServe != null) {
            val deadline =
                if (effectiveServe == 0L) null else TimeSource.Monotonic.markNow() + effectiveServe.milliseconds
            while (deadline == null || !deadline.hasPassedNow()) {
                if (keel_shutdown_requested() != 0) {
                    Log.info("interrupt received", tag = "Server")
                    break
                }
                if (HostConsole.shouldStop) {
                    Log.info("stop requested", tag = "Server")
                    break
                }
                HostConsole.poll()
                HostPump.serve()
            }
            // 先关提示符：之后的停机日志不该再补 `> `，屏幕底部也不该留一个空提示
            ConsolePrompt.disable()
            Log.info("serve finished", tag = "Server")
        }

        HostHttp.stop()

        Log.info("shutdown", tag = "Server")
        // 先把积压的日志消费掉；停用期间 PluginRuntime.shutdown 仍会继续 serve，
        // 让插件 onDisable 里的日志 / 配置 / 存储 / 服务调用拿到应答。
        HostPump.drain()
        HostPlugins.shutdownAll()
        HostPump.drain()
    }
}
