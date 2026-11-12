package moe.lolosia.keel.host.config

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.config.*
import moe.lolosia.keel.host.plugin.HostPluginIds
import moe.lolosia.keel.host.plugin.mkdirs

/**
 * Host 侧配置文件注册表。
 *
 * 目录布局（相对 Host 的工作目录，即启动参数的缓存目录）：
 * ```
 * application.yaml
 * config/plugins/<plugin-id>.yaml
 * ```
 *
 * 插件只经 `ConfigReadRequest` / `ConfigWriteRequest` 访问；Host 是唯一写文件的一方，
 * 因此所有读写都在事件泵线程上串行完成，不需要额外加锁。
 */
internal object HostConfigs {

    private var workDir: String = "."

    private var mainline: YamlConfig? = null

    /** manifest id → 文件级 Config；停用插件时丢弃缓存，下次启动重新读盘。 */
    private val configs = mutableMapOf<String, YamlConfig>()

    fun init(dir: String) {
        workDir = dir.trimEnd('/', '\\').ifEmpty { "." }
        mainline = YamlConfig("$workDir/application.yaml")
        mkdirs("$workDir/config/plugins")
    }

    /** 插件停用 / 卸载：丢掉内存态，文件保留。 */
    fun release(pluginId: ULong) {
        val name = HostPluginIds.nameOf(pluginId) ?: return
        configs.remove(name)
    }

    /**
     * 主线 `application.yaml` 的 `server.port`；没配或不是整数时返回 null。
     *
     * 只在启动阶段读一次，用于决定 HTTP 绑定端口。
     */
    fun serverPort(): Int? {
        val config = mainline ?: return null
        return try {
            config.read(Int.serializer(), "server.port")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 日志归档容量：`log.archive.maxRecords` / `log.archive.maxMemoryBytes`。
     *
     * 任一项缺失或不是正数时返回 null，沿用 [moe.lolosia.keel.host.LogArchive] 的默认值。
     */
    fun logArchiveLimits(): Pair<Int, Long>? {
        val config = mainline ?: return null
        val records = runCatching {
            config.read(Int.serializer(), "log.archive.maxRecords")
        }.getOrNull() ?: return null
        val bytes = runCatching {
            config.read(Long.serializer(), "log.archive.maxMemoryBytes")
        }.getOrNull() ?: return null
        if (records <= 0 || bytes <= 0) return null
        return records to bytes
    }

    fun read(pluginId: ULong, request: ConfigReadRequest): ConfigReadReply {
        val config = configFor(pluginId, request.scope)
        return try {
            val element = config.read(JsonElement.serializer(), request.path)
            ConfigReadReply(
                found = true,
                value = Json.encodeToString(JsonElement.serializer(), element),
            )
        } catch (e: NoSuchElementException) {
            ConfigReadReply(found = false)
        }
    }

    fun write(pluginId: ULong, request: ConfigWriteRequest): ConfigDocReply {
        val config = configFor(pluginId, request.scope)
        config.write(JsonElement.serializer(), Json.parseToJsonElement(request.value), request.path)
        Log.info(
            "配置写入：${request.scope.name.lowercase()} " +
                "path=${request.path.ifEmpty { "<root>" }}",
            tag = "Config",
        )
        return ConfigDocReply(
            Json.encodeToString(
                JsonElement.serializer(),
                config.read(JsonElement.serializer(), ""),
            )
        )
    }

    private fun configFor(pluginId: ULong, scope: ConfigScope): YamlConfig = when (scope) {
        ConfigScope.MAIN -> mainline ?: error("HostConfigs 尚未初始化")
        ConfigScope.PLUGIN -> {
            val name = HostPluginIds.nameOf(pluginId) ?: error("插件 $pluginId 没有登记配置文件名")
            configs.getOrPut(name) { YamlConfig("$workDir/config/plugins/$name.yaml") }
        }
    }
}
