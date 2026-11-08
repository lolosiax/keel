package moe.lolosia.keel.host.config

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import moe.lolosia.keel.api.config.Config

/**
 * 文件级 [Config]：读走 [YamlDocument]，写合并后按原文编辑再原子落盘。
 *
 * 缺失路径的语义与接口约定一致：目标类型可空返回 null，否则抛 [NoSuchElementException]。
 */
internal class YamlConfig(private val file: String) : Config {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var document: YamlDocument? = null

    override fun <T> read(serializer: KSerializer<T>, path: String): T {
        val node = resolve(document().root, path)
        if (node == null) {
            if (serializer.descriptor.isNullable) {
                @Suppress("UNCHECKED_CAST")
                return null as T
            }
            throw NoSuchElementException("配置缺少路径：$file:$path")
        }
        return json.decodeFromJsonElement(serializer, node)
    }

    override fun <T> write(serializer: KSerializer<T>, value: T, path: String) {
        val incoming = json.encodeToJsonElement(serializer, value)
        val current = document()
        val merged = mergeAt(current.root, path.split('.').filter { it.isNotEmpty() }, incoming)
        val text = current.render(merged)
        ConfigFs.writeText(file, text)
        document = YamlDocument.parse(text)
    }

    /** 写入即落盘，没有待提交的缓冲。 */
    override fun save() = Unit

    override fun reload() {
        document = null
    }

    private fun document(): YamlDocument =
        document ?: YamlDocument.parse(ConfigFs.readTextOrNull(file) ?: "").also { document = it }

    private fun resolve(root: JsonElement, path: String): JsonElement? {
        if (path.isBlank()) return root
        var current = root
        for (segment in path.split('.')) {
            if (segment.isEmpty()) continue
            current = (current as? JsonObject)?.get(segment) ?: return null
        }
        return current
    }

    private fun mergeAt(root: JsonElement, path: List<String>, value: JsonElement): JsonElement {
        if (path.isEmpty()) return merge(root, value)
        val obj = root as? JsonObject ?: JsonObject(emptyMap())
        val key = path.first()
        val updated = obj.toMutableMap().apply {
            put(key, mergeAt(obj[key] ?: JsonNull, path.drop(1), value))
        }
        return JsonObject(updated)
    }

    /** 对象逐键合并，标量 / 序列整体替换；未出现在新值里的键保持原样。 */
    private fun merge(old: JsonElement?, incoming: JsonElement): JsonElement {
        if (old is JsonObject && incoming is JsonObject) {
            val result = old.toMutableMap()
            incoming.forEach { (key, value) -> result[key] = merge(old[key], value) }
            return JsonObject(result)
        }
        return incoming
    }
}
