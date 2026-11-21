// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.api.web

import kotlinx.serialization.serializer
import kotlinx.serialization.builtins.ListSerializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge
import moe.lolosia.keel.api.share.PublicApiDirectory
import moe.lolosia.keel.api.share.SharedRef

/** `moe.lolosia.keel.api.web.Context` 在消费方运行时里的代理。 */
class ContextProxy(
    private val proxyHandle: Long, private val directory: PublicApiDirectory,
) : moe.lolosia.keel.api.web.Context {

    override suspend fun components(): kotlin.collections.List<moe.lolosia.keel.api.web.Component> {
        val refs = ServiceWire.decode(ListSerializer(SharedRef.serializer()), PublicApiBridge.call(proxyHandle, "keel.context", "components", ByteArray(0)))
        return refs.map { moe.lolosia.keel.api.web.ComponentProxy(it.handle) }
    }

    override suspend fun component(id: String): moe.lolosia.keel.api.web.Component? {
        val ref = ServiceWire.decode(SharedRef.serializer(), PublicApiBridge.call(proxyHandle, "keel.context", "component", ServiceWire.encode(serializer<String>(), id)))
        return if (ref.handle == 0L) null else moe.lolosia.keel.api.web.ComponentProxy(ref.handle)
    }

    override suspend fun addComponent(component: moe.lolosia.keel.api.web.Component): Unit {
        PublicApiBridge.call(proxyHandle, "keel.context", "addComponent", ServiceWire.encode(SharedRef.serializer(), SharedRef(directory.handleOf(component))))
    }

    override suspend fun removeComponent(id: String): moe.lolosia.keel.api.web.Component? {
        val ref = ServiceWire.decode(SharedRef.serializer(), PublicApiBridge.call(proxyHandle, "keel.context", "removeComponent", ServiceWire.encode(serializer<String>(), id)))
        return if (ref.handle == 0L) null else moe.lolosia.keel.api.web.ComponentProxy(ref.handle)
    }

    override suspend fun destroy(): Unit {
        PublicApiBridge.call(proxyHandle, "keel.context", "destroy", ByteArray(0))
    }
}
