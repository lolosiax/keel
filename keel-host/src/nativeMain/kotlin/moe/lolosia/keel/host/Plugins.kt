package moe.lolosia.keel.host

import moe.lolosia.keel.abi.c.KEEL_ABI_VERSION
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.classpath.EmptyClasspath
import moe.lolosia.keel.host.classpath.HostClasspath
import moe.lolosia.keel.host.plugin.HostPump
import moe.lolosia.keel.host.plugin.PluginLoader
import moe.lolosia.keel.host.plugin.PluginNames
import moe.lolosia.keel.host.plugin.PluginRuntime
import moe.lolosia.keel.host.plugin.currentTargetName
import moe.lolosia.keel.host.plugin.startPlugin

/**
 * `--plugins <dir> [cacheDir]`：扫描目录、校验 `.keel`、解出动态库并启动插件。
 *
 * 启动成功后每个插件都在自己的 Worker 线程上跑；进程退出前逐个投 SHUTDOWN。
 */
internal actual fun loadPluginsFromCli(pluginsDir: String, cacheDir: String?) {
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
    Log.info("plugins: $dir", tag = "loader")
    Log.info("cache: $cache", tag = "loader")
    Log.info("loaded ${result.loaded.size} plugin(s)", tag = "loader")

    val runtimes = mutableListOf<PluginRuntime>()
    var startFailures = 0
    var nextPluginId = 1uL

    result.loaded.forEach { plugin ->
        val manifest = plugin.manifest
        val authors = manifest.authors.joinToString().ifBlank { "unknown" }
        val libEntry = manifest.libraryFor(currentTargetName) ?: "?"
        Log.info(
            "loaded ${manifest.name.ifBlank { manifest.id }} (${manifest.id}) " +
                "v${manifest.version} by $authors",
            tag = "loader",
        )
        Log.info("  source: ${plugin.keelPath}!/$libEntry", tag = "loader")
        Log.info("  resources: ${plugin.classpathRef.entries}", tag = "loader")
        try {
            val pluginId = nextPluginId++
            PluginNames.register(pluginId, manifest.id)
            val runtime = startPlugin(plugin, pluginId)
            runtimes += runtime
            Log.info("started ${manifest.id} (plugin_id=${runtime.pluginId})")
        } catch (e: Exception) {
            startFailures++
            Log.warn("start failed: ${manifest.id}: ${e.message}", tag = "loader")
        }
    }

    HostPump.drain()

    val failureCount = result.failed.size + startFailures
    if (failureCount > 0) {
        Log.warn("failed ($failureCount):", tag = "loader")
        result.failed.forEach { failure ->
            val id = failure.pluginId?.let { "$it " }.orEmpty()
            Log.warn("  $id${failure.keelPath}: ${failure.reason}", tag = "loader")
        }
    } else {
        Log.info("failed: 0", tag = "loader")
    }

    if (runtimes.isNotEmpty()) {
        Log.info("shutdown")
        runtimes.forEach { it.shutdown() }
        HostPump.drain()
        Log.info("stopped ${runtimes.size} plugin(s)")
    }
}
