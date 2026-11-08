package moe.lolosia.keel.host.capability

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.host.plugin.setHostDatabase

/**
 * Host 侧已安装能力包的注册表。
 *
 * 能力包在插件扫描阶段解包（[install]），在启动 native 插件之前 `open`（[activateDatabase]）；
 * 业务插件通过 `plugin.json.requires` 声明依赖，缺少时拒绝启用（见 `HostPlugins.start`）。
 */
internal object HostCapabilities {

    private var loader: CapabilityLoader? = null
    private val installed = mutableListOf<InstalledCapability>()
    private val active = mutableSetOf<String>()
    private var database: DatabaseCapability? = null

    /** 依赖不满足、不能激活的能力包 id。 */
    private val unsatisfied = mutableSetOf<String>()

    fun init(cacheRoot: String) {
        loader = CapabilityLoader(cacheRoot)
    }

    /** 解包一个 `kind: capability` 的包；失败由调用方计入加载失败。 */
    fun install(keelPath: String) {
        val entry = requireLoader().install(keelPath)
        installed += entry
        Log.info(
            "capability ${entry.manifest.id} v${entry.manifest.version}" +
                " provides=${entry.manifest.provides} schemes=${entry.manifest.schemes}",
            tag = "Loader",
        )
    }

    /** `requires` 里声明的能力是否已经 `open`。 */
    fun isAvailable(name: String): Boolean = name in active

    /** 已安装能力包声明的全部能力名（不要求已经 open）。 */
    fun installedProvides(): Set<String> =
        installed.flatMapTo(mutableSetOf()) { it.manifest.provides }

    /**
     * 校验能力包之间的 `requires`：提供者必须已安装。
     *
     * 依赖不满足的包不会被 [activateDatabase] 选中；返回这些包的 id。
     */
    fun validateDependencies(): Set<String> {
        val provided = installedProvides()
        unsatisfied.clear()
        installed.forEach { capability ->
            val missing = capability.manifest.requires.filter {
                it == capability.manifest.id || it !in provided
            }
            if (missing.isEmpty()) return@forEach
            unsatisfied += capability.manifest.id
            Log.warn(
                "capability ${capability.manifest.id} 缺少依赖能力 ${missing.joinToString()}",
                tag = "Loader",
            )
        }
        return unsatisfied.toSet()
    }

    /**
     * 按 `database.url` 的 scheme 选中能力包并 `open`。
     *
     * 找不到、多个命中且未配 `database.capability`、`open` 失败都只记录日志：
     * 依赖 database 的插件会被拒绝启用，其余插件与 Host 继续运行。
     */
    fun activateDatabase(settings: DatabaseSettings?) {
        if (settings == null) {
            Log.info("database: 未配置 KEEL_DB_URL，跳过", tag = "Loader")
            return
        }
        if (database != null) return

        val candidates = installed.filter {
            "database" in it.manifest.provides && it.manifest.id !in unsatisfied
        }
        val selected = select(candidates, settings) ?: return
        try {
            val opened = DatabaseCapability.open(selected, settings)
            database = opened
            setHostDatabase(opened.apiPointer, opened.handle)
            active += "database"
            Log.info(
                "database: ${selected.manifest.id}（scheme=${settings.scheme}, url=${settings.url}）",
                tag = "Loader",
            )
        } catch (e: Exception) {
            Log.warn("database 能力打开失败：${selected.manifest.id}: ${e.message}", tag = "Loader")
        }
    }

    /** 进程退出时关闭能力包；调用前所有 native 插件必须已停。 */
    fun closeAll() {
        database?.let { opened ->
            runCatching { opened.close() }
                .onFailure { Log.warn("database close 失败：${it.message}", tag = "Loader") }
        }
        database = null
        setHostDatabase(null, null)
        active.clear()
    }

    private fun select(
        candidates: List<InstalledCapability>,
        settings: DatabaseSettings,
    ): InstalledCapability? {
        val explicit = settings.capability?.takeIf { it.isNotBlank() }
        if (explicit != null) {
            val match = candidates.firstOrNull { it.manifest.id == explicit }
            if (match == null) {
                Log.warn(
                    "database.capability=$explicit 未安装（已装：${ids(candidates)}）",
                    tag = "Loader",
                )
            }
            return match
        }

        val byScheme = candidates.filter { settings.scheme in it.manifest.schemes }
        return when {
            byScheme.size == 1 -> byScheme.first()

            byScheme.isEmpty() -> {
                Log.warn(
                    "database.url 的 scheme=${settings.scheme} 没有匹配的能力包" +
                        "（已装：${ids(candidates)}）",
                    tag = "Loader",
                )
                null
            }

            else -> {
                Log.warn(
                    "scheme=${settings.scheme} 有多个能力包（${ids(byScheme)}），" +
                        "请配置 database.capability",
                    tag = "Loader",
                )
                null
            }
        }
    }

    private fun ids(packages: List<InstalledCapability>): String =
        packages.joinToString { "${it.manifest.id}(${it.manifest.schemes.joinToString("/")})" }
            .ifEmpty { "无" }

    private fun requireLoader(): CapabilityLoader =
        loader ?: error("HostCapabilities 尚未初始化")
}
