package moe.lolosia.keel.host

import moe.lolosia.keel.classpath.EmptyClasspath
import moe.lolosia.keel.host.classpath.HostClasspath
import moe.lolosia.keel.host.plugin.PluginLoader
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

    println("target: $currentTargetName")
    println("plugins: $dir")
    println("cache: $cache")

    val runtimes = mutableListOf<PluginRuntime>()
    var startFailures = 0
    var nextPluginId = 1uL

    println("loaded (${result.loaded.size}):")
    result.loaded.forEach { plugin ->
        val manifest = plugin.manifest
        println("  ${manifest.id}@${manifest.version} abi=${manifest.abiVersion} entry=${manifest.entry}")
        println("    lib: ${plugin.libraryPath}")
        println("    resources: ${plugin.classpathRef.entries}")
        try {
            val runtime = startPlugin(plugin, nextPluginId++)
            runtimes += runtime
            println("    started: plugin_id=${runtime.pluginId}")
        } catch (e: Exception) {
            startFailures++
            println("    start failed: ${e.message}")
        }
    }

    println("failed (${result.failed.size + startFailures}):")
    result.failed.forEach { failure ->
        val id = failure.pluginId?.let { "$it " }.orEmpty()
        println("  $id${failure.keelPath}: ${failure.reason}")
    }

    if (runtimes.isNotEmpty()) {
        println("shutdown:")
        runtimes.forEach { it.shutdown() }
        println("  stopped ${runtimes.size} plugin(s)")
    }
}
