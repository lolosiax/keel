package moe.lolosia.keel.host.plugin

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.log.ModuleColors
import moe.lolosia.keel.host.classpath.HostClasspath
import moe.lolosia.keel.host.route.HostRoutes

/**
 * Host 侧已安装插件的注册表。
 *
 * 启动时由 `Plugins.kt` 调用 [init] 与 [loadAll]；之后控制台 `plugins` 命令
 * 通过这里执行安装 / 卸载 / 启停。所有方法都在主线程（事件泵线程）调用，
 * 因此不需要加锁。
 *
 * 数字 id 一旦分配就不再复用：卸载后重新安装同一包会拿到新 id；
 * 旧 id 的路由随卸载清掉，`PluginNames` 里的名称映射保留给晚到的日志解码。
 */
internal object HostPlugins {

    /** 一个已安装插件；[runtime] 为 null 表示已停用（包仍在插件目录里）。 */
    internal class Entry(
        val loaded: LoadedPlugin,
        val pluginId: ULong,
    ) {
        var runtime: PluginRuntime? = null

        val id: String get() = loaded.manifest.id
        val displayName: String get() = loaded.manifest.name.ifBlank { id }
        val enabled: Boolean get() = runtime != null
    }

    private val entries = mutableListOf<Entry>()
    private var loader: PluginLoader? = null
    private var classpath: HostClasspath? = null
    private var directory: String = ""
    private var nextId: ULong = 1uL
    private var failedStarts = 0

    val pluginsDirectory: String get() = directory

    /** 启动阶段记录下的失败次数，供 Loader 汇总打印。 */
    val startFailures: Int get() = failedStarts

    fun init(host: HostClasspath, cacheDir: String, pluginsDir: String) {
        classpath = host
        directory = pluginsDir
        loader = PluginLoader(host, cacheDir)
    }

    fun list(): List<Entry> = entries.toList()

    fun enabled(): List<Entry> = entries.filter { it.enabled }

    fun find(id: String): Entry? = entries.firstOrNull { it.id == id }

    /** 扫描插件目录并逐个启动；启动失败计入 [startFailures]，不中断其它插件。 */
    fun loadAll(): PluginLoadResult {
        val result = requireLoader().loadAll(directory)
        result.loaded.forEach { loaded ->
            logPackage(loaded)
            val entry = Entry(loaded, nextId++)
            entries += entry
            start(entry)
        }
        return result
    }

    /**
     * 安装一个 `.keel`：复制进插件目录 → 校验 → 启动。
     *
     * @throws IllegalArgumentException 包名非法、目标同名文件已存在、manifest 校验失败
     */
    fun install(source: String): Boolean {
        val name = source.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')
        require(name.endsWith(".keel")) { "不是 .keel 包：$source" }

        val target = "$directory/$name"
        val copied = source != target
        if (copied) {
            require(fileSizeOrNull(target) == null) { "插件目录里已存在同名包：$name" }
            PluginFs.writeBytes(target, PluginFs.readBytes(source))
        }

        val loaded = try {
            requireLoader().load(target)
        } catch (e: Throwable) {
            if (copied) PluginFs.delete(target)
            throw e
        }

        val entry = Entry(loaded, nextId++)
        logPackage(loaded)
        if (!start(entry)) {
            classpath?.unregister(entry.id)
            if (copied) PluginFs.delete(target)
            return false
        }

        entries += entry
        Log.info("installed ${entry.id}", tag = "Loader")
        return true
    }

    /**
     * 卸载：停用 → 清路由 / 名称 / classpath → 删除包文件。
     *
     * Kotlin/Native 运行时不支持 `dlclose`，动态库仍留在进程地址空间里，
     * 这里只是让所有引用失效并从插件目录移除部署单元。
     */
    fun uninstall(id: String): Boolean {
        val entry = find(id) ?: return false
        stop(entry)
        entries.remove(entry)
        classpath?.unregister(id)
        // 不删 PluginNames：数字 id 永不复用，晚到的日志仍该显示插件名
        val deleted = if (isInstalledPackage(entry.loaded.keelPath)) {
            PluginFs.delete(entry.loaded.keelPath)
        } else {
            Log.warn("包不在插件目录内，保留文件：${entry.loaded.keelPath}", tag = "Loader")
            false
        }
        Log.info("uninstalled $id，deleted=$deleted，动态库仍留在进程内", tag = "Loader")
        return true
    }

    /** 重新启动一个已停用的插件；已经启用时返回 false。 */
    fun enable(id: String): Boolean {
        val entry = find(id) ?: return false
        if (entry.enabled) return false
        return start(entry)
    }

    /** 停用插件：投 SHUTDOWN 并清掉它的路由；包与 classpath 保留。 */
    fun disable(id: String): Boolean {
        val entry = find(id) ?: return false
        if (!entry.enabled) return false
        stop(entry)
        Log.info("disabled $id", tag = "Loader")
        return true
    }

    /** 进程退出前逐个停用。 */
    fun shutdownAll() {
        val running = entries.filter { it.enabled }
        running.forEach { stop(it) }
        if (running.isNotEmpty()) Log.info("stopped ${running.size} plugin(s)", tag = "Server")
    }

    private fun start(entry: Entry): Boolean {
        val manifest = entry.loaded.manifest
        return try {
            PluginNames.register(entry.pluginId, entry.displayName)
            val runtime = startPlugin(entry.loaded, entry.pluginId)
            entry.runtime = runtime
            Log.info("started ${manifest.id} (plugin_id=${runtime.pluginId})")
            true
        } catch (e: Exception) {
            failedStarts++
            Log.warn("start failed: ${manifest.id}: ${e.message}", tag = "Loader")
            false
        }
    }

    private fun stop(entry: Entry) {
        entry.runtime?.shutdown()
        entry.runtime = null
        HostRoutes.remove(entry.pluginId)
    }

    private fun logPackage(loaded: LoadedPlugin) {
        val manifest = loaded.manifest
        val displayName = manifest.name.ifBlank { manifest.id }
        manifest.color?.let { hex ->
            if (!ModuleColors.register(displayName, hex)) {
                Log.warn("invalid color '$hex' for ${manifest.id}", tag = "Loader")
            }
        }
        val authors = manifest.authors.joinToString().ifBlank { "unknown" }
        val libEntry = manifest.libraryFor(currentTargetName) ?: "?"
        val build = manifest.buildInfo
        val buildText = if (build == null) "" else " build ${build.gitSha} (${build.commitTime})"
        Log.info(
            "loaded $displayName (${manifest.id}) v${manifest.version}$buildText by $authors",
            tag = "Loader",
        )
        Log.info("  source: ${loaded.keelPath}!/$libEntry", tag = "Loader")
        Log.info("  resources: ${loaded.classpathRef.entries}", tag = "Loader")
    }

    private fun isInstalledPackage(path: String): Boolean {
        val prefix = directory.trimEnd('/', '\\').replace('\\', '/') + "/"
        return path.replace('\\', '/').startsWith(prefix)
    }

    private fun requireLoader(): PluginLoader = loader ?: error("HostPlugins 尚未初始化")
}
