// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.api.worker

import kotlinx.serialization.serializer
import kotlinx.serialization.builtins.ListSerializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiDirectory
import moe.lolosia.keel.api.share.PublicApiDispatcher
import moe.lolosia.keel.api.share.SharedRef

/** `moe.lolosia.keel.api.worker.RequestContextImpl` 的提供者侧分发器；覆盖 moe.lolosia.keel.api.web.Context, moe.lolosia.keel.api.web.WebContext。 */
internal class RequestContextImplDispatcher(
    private val impl: moe.lolosia.keel.api.worker.RequestContextImpl, private val directory: PublicApiDirectory,
) : PublicApiDispatcher {

    override val typeId: String = "keel.context"

    override suspend fun dispatch(
        handle: Long,
        method: String,
        payload: ByteArray,
        callerModule: Long,
        overrides: Set<String>,
    ): ByteArray = when (method) {
        "components" -> {
            val value = impl.components()
            ServiceWire.encode(ListSerializer(SharedRef.serializer()), value.map { SharedRef(directory.handleOf(it)) })
        }

        "component" -> {
            val id = ServiceWire.decode(serializer<String>(), payload)
            val value = impl.component(id)
            ServiceWire.encode(SharedRef.serializer(), if (value == null) SharedRef(0) else SharedRef(directory.handleOf(value)))
        }

        "addComponent" -> {
            val component = moe.lolosia.keel.api.web.ComponentProxy(ServiceWire.decode(SharedRef.serializer(), payload).handle)
            impl.addComponent(component)
            ByteArray(0)
        }

        "removeComponent" -> {
            val id = ServiceWire.decode(serializer<String>(), payload)
            val value = impl.removeComponent(id)
            ServiceWire.encode(SharedRef.serializer(), if (value == null) SharedRef(0) else SharedRef(directory.handleOf(value)))
        }

        "destroy" -> {
            impl.destroy()
            ByteArray(0)
        }

        "webRequest" -> {
            val value = impl.webRequest()
            ServiceWire.encode(SharedRef.serializer(), if (value == null) SharedRef(0) else SharedRef(directory.handleOf(value)))
        }

        else -> throw IllegalArgumentException("RequestContextImpl 没有方法 $method")
    }
}
