package moe.lolosia.keel.host

import moe.lolosia.keel.abi.c.KEEL_SUBJECT_PING
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.classpath.EmptyClasspath
import moe.lolosia.keel.host.classpath.HostClasspath
import moe.lolosia.keel.host.plugin.HostPlugins
import moe.lolosia.keel.host.plugin.HostPump

/**
 * `--plugins <dir> [cacheDir] [--http=<port>] [--serve | --serve-ms=<n>]`：
 * 扫描目录、校验 `.keel`、解出动态库并启动插件。
 *
 * 插件清单与启停状态由 [HostPlugins] 持有，控制台命令与应用生命周期共用同一份；
 * 这里只负责启动阶段的日志、体检与把运行期交给 `HostRuntime`。
 */
internal actual fun loadPluginsFromCli(
    pluginsDir: String,
    cacheDir: String?,
    serveMillis: Long?,
    httpPort: Int?,
) {
    val dir = pluginsDir.trimEnd('/', '\\')
    val cache = cacheDir?.trimEnd('/', '\\') ?: "$dir/.cache"

    // 尽早接管 Ctrl+C，启动阶段的中断也走优雅停机
    HostBootstrap.installShutdownHook()

    val host = HostClasspath(EmptyClasspath)
    HostPlugins.init(host, cache, dir)
    val result = HostPlugins.loadAll()

    Log.info("plugins: $dir", tag = "Loader")
    Log.info("cache: $cache", tag = "Loader")
    Log.info("loaded ${result.loaded.size} plugin(s)", tag = "Loader")

    // 最小请求 / 应答回路：确认每个插件都能收到事件并按 correlation 回包
    HostPlugins.enabled().forEach { entry ->
        val ok = HostPump.requestPlugin(entry.pluginId, KEEL_SUBJECT_PING.toULong())
        if (ok) {
            Log.info("ping ${entry.id} ok", tag = "Loader")
        } else {
            Log.warn("ping ${entry.id} 未响应", tag = "Loader")
        }
    }

    HostPump.drain()

    val failureCount = result.failed.size + HostPlugins.startFailures
    if (failureCount > 0) {
        Log.warn("failed ($failureCount):", tag = "Loader")
        result.failed.forEach { failure ->
            val id = failure.pluginId?.let { "$it " }.orEmpty()
            Log.warn("  $id${failure.keelPath}: ${failure.reason}", tag = "Loader")
        }
    } else {
        Log.info("failed: 0", tag = "Loader")
    }

    // 运行期交给 HostRuntime：HTTP 入口、控制台、事件循环、停机
    HostRuntime.run(serveMillis, httpPort)
}
