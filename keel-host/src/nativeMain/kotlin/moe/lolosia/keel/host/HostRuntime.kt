package moe.lolosia.keel.host

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.host.plugin.HostPump
import moe.lolosia.keel.host.plugin.PluginRuntime

/**
 * Host 运行期：HTTP 入口、事件循环与优雅停机。
 *
 * 插件扫描、加载与启动由 `Plugins.kt` 负责；这里只接管「跑起来之后」的事情。
 */
internal object HostRuntime {

    fun run(runtimes: List<PluginRuntime>, serveMillis: Long?, httpPort: Int?) {
        // 有 HTTP 但没有显式 serve 选项时常驻，否则服务一启动就退出
        val effectiveServe = serveMillis ?: if (httpPort != null) 0L else null
        httpPort?.let { HostHttp.start(it, runtimes.firstOrNull()?.pluginId) }

        if (effectiveServe != null) {
            val label = if (effectiveServe == 0L) "直到进程结束" else "${effectiveServe}ms"
            Log.info("serving ($label)", tag = "Server")
            val deadline =
                if (effectiveServe == 0L) null else TimeSource.Monotonic.markNow() + effectiveServe.milliseconds
            while (deadline == null || !deadline.hasPassedNow()) {
                HostPump.serve()
            }
            Log.info("serve finished", tag = "Server")
        }

        HostHttp.stop()

        if (runtimes.isNotEmpty()) {
            Log.info("shutdown", tag = "Server")
            runtimes.forEach { it.shutdown() }
            HostPump.drain()
            Log.info("stopped ${runtimes.size} plugin(s)", tag = "Server")
        }
    }
}
