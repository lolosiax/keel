package moe.lolosia.keel.host

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import moe.lolosia.keel.abi.c.KEEL_ABI_VERSION
import moe.lolosia.keel.abi.c.KEEL_SUBJECT_PING
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.log.ModuleColors
import moe.lolosia.keel.classpath.EmptyClasspath
import moe.lolosia.keel.host.classpath.HostClasspath
import moe.lolosia.keel.host.plugin.HostPump
import moe.lolosia.keel.host.plugin.PluginLoader
import moe.lolosia.keel.host.plugin.PluginNames
import moe.lolosia.keel.host.plugin.PluginRuntime
import moe.lolosia.keel.host.plugin.currentTargetName
import moe.lolosia.keel.host.plugin.startPlugin

/**
 * `--plugins <dir> [cacheDir] [--http=<port>] [--serve | --serve-ms=<n>]`：
 * 扫描目录、校验 `.keel`、解出动态库并启动插件。
 *
 * 启动成功后每个插件都在自己的 Worker 线程上跑；`--http` 启动 Host 的 Ktor 引擎，
 * 带 serve 选项时主线程进入事件循环，结束时先停 HTTP 再逐个投 SHUTDOWN。
 */
internal actual fun loadPluginsFromCli(
    pluginsDir: String,
    cacheDir: String?,
    serveMillis: Long?,
    httpPort: Int?,
) {
    val dir = pluginsDir.trimEnd('/', '\\')
    val cache = cacheDir?.trimEnd('/', '\\') ?: "$dir/.cache"
    val host = HostClasspath(EmptyClasspath)
    val result = PluginLoader(host, cache).loadAll(dir)

    printBanner(KEEL_VERSION, currentTargetName)
    Log.info("version: $KEEL_VERSION")
    Log.info("executable: ${executablePath()}")
    Log.info("working dir: ${currentWorkingDirectory()}")
    Log.info("os: ${platformDescription()}")
    Log.info("target: $currentTargetName")
    Log.info("abi: $KEEL_ABI_VERSION")
    Log.info("plugins: $dir", tag = "Loader")
    Log.info("cache: $cache", tag = "Loader")
    Log.info("loaded ${result.loaded.size} plugin(s)", tag = "Loader")

    val runtimes = mutableListOf<PluginRuntime>()
    var startFailures = 0
    var nextPluginId = 1uL

    result.loaded.forEach { plugin ->
        val manifest = plugin.manifest
        val displayName = manifest.name.ifBlank { manifest.id }
        manifest.color?.let { hex ->
            if (!ModuleColors.register(displayName, hex)) {
                Log.warn("invalid color '$hex' for ${manifest.id}", tag = "Loader")
            }
        }
        val authors = manifest.authors.joinToString().ifBlank { "unknown" }
        val libEntry = manifest.libraryFor(currentTargetName) ?: "?"
        Log.info(
            "loaded $displayName (${manifest.id}) v${manifest.version} by $authors",
            tag = "Loader",
        )
        Log.info("  source: ${plugin.keelPath}!/$libEntry", tag = "Loader")
        Log.info("  resources: ${plugin.classpathRef.entries}", tag = "Loader")
        try {
            val pluginId = nextPluginId++
            PluginNames.register(pluginId, displayName)
            val runtime = startPlugin(plugin, pluginId)
            runtimes += runtime
            Log.info("started ${manifest.id} (plugin_id=${runtime.pluginId})")
        } catch (e: Exception) {
            startFailures++
            Log.warn("start failed: ${manifest.id}: ${e.message}", tag = "Loader")
        }
    }

    // 最小请求 / 应答回路：确认每个插件都能收到事件并按 correlation 回包
    runtimes.forEach { runtime ->
        val ok = HostPump.requestPlugin(runtime.pluginId, KEEL_SUBJECT_PING.toULong())
        if (ok) {
            Log.info("ping ${runtime.pluginName} ok", tag = "Loader")
        } else {
            Log.warn("ping ${runtime.pluginName} 未响应", tag = "Loader")
        }
    }

    HostPump.drain()

    val failureCount = result.failed.size + startFailures
    if (failureCount > 0) {
        Log.warn("failed ($failureCount):", tag = "Loader")
        result.failed.forEach { failure ->
            val id = failure.pluginId?.let { "$it " }.orEmpty()
            Log.warn("  $id${failure.keelPath}: ${failure.reason}", tag = "Loader")
        }
    } else {
        Log.info("failed: 0", tag = "Loader")
    }

    // 有 HTTP 但没有显式 serve 选项时常驻，否则服务一启动就退出
    val effectiveServe = serveMillis ?: if (httpPort != null) 0L else null
    httpPort?.let { HostHttp.start(it) }

    if (effectiveServe != null) {
        val label = if (effectiveServe == 0L) "直到进程结束" else "${effectiveServe}ms"
        Log.info("serving ($label)", tag = "Host")
        val deadline =
            if (effectiveServe == 0L) null else TimeSource.Monotonic.markNow() + effectiveServe.milliseconds
        while (deadline == null || !deadline.hasPassedNow()) {
            HostPump.serve()
        }
        Log.info("serve finished", tag = "Host")
    }

    HostHttp.stop()

    if (runtimes.isNotEmpty()) {
        Log.info("shutdown")
        runtimes.forEach { it.shutdown() }
        HostPump.drain()
        Log.info("stopped ${runtimes.size} plugin(s)")
    }
}
