package moe.lolosia.keel.host.plugin

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.host.ClasspathNames
import moe.lolosia.keel.api.log.ModuleColors
import moe.lolosia.keel.classpath.Classpath
import moe.lolosia.keel.host.auth.HostAuth
import moe.lolosia.keel.host.capability.DatabaseSettings
import moe.lolosia.keel.host.capability.HostCapabilities
import moe.lolosia.keel.host.classpath.HostClasspath
import moe.lolosia.keel.host.config.HostConfigs
import moe.lolosia.keel.host.route.HostPermissionCatalog
import moe.lolosia.keel.host.route.HostRoutes
import moe.lolosia.keel.host.service.HostServices
import moe.lolosia.keel.host.storage.HostStorage

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

    /** 实际启用顺序；停用时按它反序，保证依赖方先停。 */
    private val started = mutableListOf<Entry>()

    val pluginsDirectory: String get() = directory

    /** 启动阶段记录下的失败次数，供 Loader 汇总打印。 */
    val startFailures: Int get() = failedStarts

    fun init(host: HostClasspath, cacheDir: String, pluginsDir: String) {
        classpath = host
        directory = pluginsDir
        loader = PluginLoader(host, cacheDir)
        HostCapabilities.init(cacheDir)
        HostConfigs.init(cacheDir)
        HostStorage.init(cacheDir)
        PluginResources.init(cacheDir)
    }

    /**
     * 按命名空间取资源视图；[ClasspathNames.HOST] 是 Host 自己的 classpath。
     *
     * 返回的引用绑定注册时的 generation，插件卸载后读取会立即失效。
     */
    fun classpathOf(namespace: String): Classpath? {
        val host = classpath ?: return null
        return if (namespace == ClasspathNames.HOST) host.serverClasspath
        else host.registry.get(namespace)
    }

    fun list(): List<Entry> = entries.toList()

    fun enabled(): List<Entry> = entries.filter { it.enabled }

    fun find(id: String): Entry? = entries.firstOrNull { it.id == id }

    /**
     * 扫描插件目录与内嵌 bundle 并启动插件。
     *
     * 顺序固定为：磁盘能力包 → 内嵌能力包 → 磁盘插件 → 内嵌插件。
     * 能力包必须先就绪，否则 `requires: ["database"]` 的插件会被拒绝启用；
     * 两类来源都装完才统一校验依赖、激活数据库。
     *
     * 同 id 以磁盘为准：磁盘 manifest 能被解析就算「认领」该 id，
     * 即使随后加载失败也不再回退内嵌版本（否则会出现「磁盘版本坏了却悄悄跑旧版」）。
     *
     * @param bundle 可执行文件内嵌的 bundle；无内嵌时传 null
     */
    fun loadAll(bundle: PluginBundle? = null): PluginLoadResult {
        val scan = requireLoader().scanDirectory(directory)
        val loaded = mutableListOf<LoadedPlugin>()
        val failed = scan.failed.toMutableList()

        val claimed = scan.packages.mapTo(mutableSetOf()) { it.manifest.id }
        val bundleEntries = bundle?.entries.orEmpty()
        if (bundle != null) {
            Log.info(
                "bundle: ${bundle.origin}（${bundle.entries.size} 个内嵌包，" +
                    "被磁盘覆盖 ${bundle.entries.count { it.id in claimed }} 个）",
                tag = "Loader",
            )
        }

        installCapabilities(scan.packages.filter { it.manifest.isCapability }, failed)
        installCapabilities(
            bundleEntries.filter { it.manifest.isCapability && it.id !in claimed }
                .map { bundle!!.open(it) },
            failed,
        )

        scan.packages.filterNot { it.manifest.isCapability }
            .forEach { loadInto(it, loaded, failed) }
        bundleEntries.filter { !it.manifest.isCapability && it.id !in claimed }
            .forEach { loadInto(bundle!!.open(it), loaded, failed) }

        HostCapabilities.validateDependencies()
        HostCapabilities.activateDatabase(DatabaseSettings.fromEnvironment())

        loaded.forEach { loadedPlugin ->
            logPackage(loadedPlugin)
            entries += Entry(loadedPlugin, nextId++)
        }
        // 按 requires 拓扑启动：被依赖的插件先进入 onEnable
        orderByRequires(entries).forEach { start(it) }
        return PluginLoadResult(loaded, failed)
    }

    private fun installCapabilities(
        packages: List<PluginPackage>,
        failed: MutableList<PluginLoadFailure>,
    ) {
        packages.forEach { entry ->
            try {
                HostCapabilities.install(entry)
            } catch (e: Exception) {
                failed += PluginLoadFailure(
                    entry.origin,
                    entry.manifest.id,
                    e.message ?: "未知错误",
                )
            }
        }
    }

    private fun loadInto(
        entry: PluginPackage,
        loaded: MutableList<LoadedPlugin>,
        failed: MutableList<PluginLoadFailure>,
    ) {
        try {
            loaded += requireLoader().load(entry)
        } catch (e: Exception) {
            failed += PluginLoadFailure(entry.origin, entry.manifest.id, e.message ?: "未知错误")
        }
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

        val manifest = PluginPackageReader.read(target)
        if (manifest.isCapability) {
            try {
                HostCapabilities.install(target)
            } catch (e: Throwable) {
                if (copied) PluginFs.delete(target)
                throw e
            }
            HostCapabilities.activateDatabase(DatabaseSettings.fromEnvironment())
            Log.info("installed capability ${target.substringAfterLast('/')}", tag = "Loader")
            return true
        }

        // 内嵌插件已在进程里：K/N 无法卸载已加载的库，只能重启后由磁盘目录覆盖。
        // 这里保留刚复制进来的包，不在用户不知情的情况下删掉他的文件。
        if (find(manifest.id)?.loaded?.bundled == true) {
            throw IllegalArgumentException(
                "${manifest.id} 来自内嵌 bundle，运行时无法覆盖；" +
                    "包已放到 $target，重启后生效"
            )
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
        if (refuseIfRequired(id)) return false
        stop(entry)
        entries.remove(entry)
        classpath?.unregister(id)
        // 不删 PluginNames：数字 id 永不复用，晚到的日志仍该显示插件名
        val deleted = when {
            // 内嵌插件没有独立部署文件，卸载只让引用失效
            entry.loaded.bundled -> false
            isInstalledPackage(entry.loaded.keelPath) -> PluginFs.delete(entry.loaded.keelPath)
            else -> {
                Log.warn("包不在插件目录内，保留文件：${entry.loaded.keelPath}", tag = "Loader")
                false
            }
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
        if (refuseIfRequired(id)) return false
        stop(entry)
        Log.info("disabled $id", tag = "Loader")
        return true
    }

    /** 进程退出前按启用反序停用；最后关闭能力包。 */
    fun shutdownAll() {
        val running = started.asReversed().toList()
        running.forEach { stop(it) }
        if (running.isNotEmpty()) Log.info("stopped ${running.size} plugin(s)", tag = "Server")
        HostCapabilities.closeAll()
    }

    /**
     * 阻止停用一个仍被启用插件依赖的插件。
     *
     * 返回 true 表示已拒绝；调用方据此返回 false（控制台会提示详见日志）。
     */
    private fun refuseIfRequired(id: String): Boolean {
        val dependents = started.filter { it.id != id && id in it.loaded.manifest.requires }
        if (dependents.isEmpty()) return false
        Log.warn(
            "拒绝停用 $id：仍被 ${dependents.joinToString { it.id }} 依赖",
            tag = "Loader",
        )
        return true
    }

    /**
     * 按 `requires` 拓扑排序；只把「需要另一个已加载插件」的条目当作依赖边，
     * 能力名（如 `database`）不参与排序。成环时保留原顺序，让 [start] 报缺失依赖。
     */
    private fun orderByRequires(all: List<Entry>): List<Entry> {
        val byId = all.associateBy { it.id }
        val placed = mutableSetOf<String>()
        val ordered = mutableListOf<Entry>()
        val remaining = all.toMutableList()

        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { entry ->
                entry.loaded.manifest.requires.none { it in byId && it !in placed && it != entry.id }
            }
            if (ready.isEmpty()) {
                Log.warn("插件依赖成环：${remaining.joinToString { it.id }}", tag = "Loader")
                ordered += remaining
                break
            }
            ready.forEach {
                placed += it.id
                ordered += it
            }
            remaining.removeAll(ready)
        }
        return ordered
    }

    private fun start(entry: Entry): Boolean {
        val manifest = entry.loaded.manifest
        val missing = missingRequires(manifest)
        if (missing.isNotEmpty()) {
            failedStarts++
            val detail = missing.joinToString { name ->
                if (name in HostCapabilities.installedProvides()) "$name（能力未就绪）"
                else "$name（插件未启用）"
            }
            Log.warn("start failed: ${manifest.id}: 缺少依赖 $detail", tag = "Loader")
            return false
        }
        // 同一 SPI 只允许一个提供者：第二个声明直接拒绝启用
        val conflict = HostAuth.conflict(entry.pluginId, manifest.provides)
        if (conflict != null) {
            failedStarts++
            Log.warn("start failed: ${manifest.id}: $conflict", tag = "Loader")
            return false
        }
        // 配置文件名与存储命名空间都取自 manifest id；必须在 onLoad 之前登记
        HostPluginIds.register(entry.pluginId, manifest.id)
        return try {
            PluginNames.register(entry.pluginId, entry.displayName)
            // 每次启用分配新的资源目录：已加载的 DLL 留在旧目录里，不会被覆盖
            PluginResources.beginLoad(entry.pluginId, entry.loaded)
            val runtime = startPlugin(entry.loaded, entry.pluginId)
            entry.runtime = runtime
            started += entry
            // onEnable 正常返回后才对外提供 SPI，避免半启动的插件接到认证请求
            HostAuth.claim(entry.pluginId, manifest.provides)
            Log.info("started ${manifest.id} (plugin_id=${runtime.pluginId})")
            true
        } catch (e: Exception) {
            failedStarts++
            PluginResources.endLoad(entry.pluginId)
            // 插件在 onEnable 之前已经上报过服务声明，启动失败时一并撤回
            HostRoutes.remove(entry.pluginId)
            HostPermissionCatalog.remove(entry.pluginId)
            HostServices.remove(entry.pluginId)
            HostAuth.release(entry.pluginId)
            HostConfigs.release(entry.pluginId)
            HostPluginIds.release(entry.pluginId)
            Log.warn("start failed: ${manifest.id}: ${e.message}", tag = "Loader")
            false
        }
    }

    private fun stop(entry: Entry) {
        entry.runtime?.shutdown()
        entry.runtime = null
        started.remove(entry)
        PluginResources.endLoad(entry.pluginId)
        HostRoutes.remove(entry.pluginId)
        HostPermissionCatalog.remove(entry.pluginId)
        HostServices.remove(entry.pluginId)
        HostAuth.release(entry.pluginId)
        HostConfigs.release(entry.pluginId)
        HostPluginIds.release(entry.pluginId)
        HostPump.dropPlugin(entry.pluginId)
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

    /**
     * `requires` 里未满足的依赖。
     *
     * 名字先按能力包提供的 `provides` 解释（必须已 open），否则当作插件 id（必须已启用）。
     */
    private fun missingRequires(manifest: KeelPackageManifest): List<String> {
        val capabilities = HostCapabilities.installedProvides()
        return manifest.requires.filter { name ->
            if (name in capabilities) !HostCapabilities.isAvailable(name)
            else find(name)?.enabled != true
        }
    }
}
