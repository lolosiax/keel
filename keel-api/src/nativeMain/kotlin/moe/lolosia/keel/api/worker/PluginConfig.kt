package moe.lolosia.keel.api.worker

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import moe.lolosia.keel.api.config.*
import moe.lolosia.keel.api.host.*
import moe.lolosia.keel.api.service.ServiceWire

/**
 * 插件侧的 [Config]：本地快照 + Host 往返。
 *
 * - 首次读取时向 Host 要整棵文档并缓存，之后读取零往返；
 * - 写入把 `JsonElement` 发给 Host，由 Host 合并、保留注释并原子落盘；
 * - `save` 是空操作：Host 每次写入都已经落盘；
 * - `reload` 重新拉取整棵文档。
 *
 * 只能在 Worker 线程调用；等待期间会重入事件泵（与跨模块调用同一规则）。
 */
internal class PluginConfig(
    private val link: HostLink,
    private val scope: ConfigScope,
) : Config {

    /** 配置里多出来的键（其它插件版本写的）直接忽略；写入时把默认值也带上，避免漏改。 */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var document: JsonElement? = null

    override fun <T> read(serializer: KSerializer<T>, path: String): T {
        val node = resolve(document ?: fetchDocument(), path)
        if (node == null) {
            if (serializer.descriptor.isNullable) {
                @Suppress("UNCHECKED_CAST")
                return null as T
            }
            throw NoSuchElementException("配置缺少路径：${scope.name.lowercase()}:$path")
        }
        return json.decodeFromJsonElement(serializer, node)
    }

    override fun <T> write(serializer: KSerializer<T>, value: T, path: String) {
        val element = json.encodeToJsonElement(serializer, value)
        val payload = ServiceWire.encode(
            ConfigWriteRequest.serializer(),
            ConfigWriteRequest(
                scope = scope,
                path = path,
                value = json.encodeToString(JsonElement.serializer(), element),
            ),
        )
        val reply = link.request(HostSubjects.CONFIG_WRITE, payload)
        document = json.parseToJsonElement(ServiceWire.decode(ConfigDocReply.serializer(), reply).document)
    }

    /** Host 写入即落盘，这里没有待提交的缓冲。 */
    override fun save() = Unit

    override fun reload() {
        document = fetchDocument()
    }

    private fun fetchDocument(): JsonElement {
        val payload = ServiceWire.encode(
            ConfigReadRequest.serializer(),
            ConfigReadRequest(scope = scope, path = ""),
        )
        val reply = ServiceWire.decode(
            ConfigReadReply.serializer(),
            link.request(HostSubjects.CONFIG_READ, payload),
        )
        return reply.value?.let { json.parseToJsonElement(it) } ?: JsonObject(emptyMap())
    }

    private fun resolve(root: JsonElement, path: String): JsonElement? {
        if (path.isBlank()) return root
        var current = root
        for (segment in path.split('.')) {
            if (segment.isEmpty()) continue
            current = (current as? JsonObject)?.get(segment) ?: return null
        }
        return current
    }
}
