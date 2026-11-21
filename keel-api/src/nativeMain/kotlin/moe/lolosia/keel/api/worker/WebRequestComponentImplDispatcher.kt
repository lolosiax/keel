// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.api.worker

import kotlinx.serialization.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiDispatcher

/** `moe.lolosia.keel.api.worker.WebRequestComponentImpl` 的提供者侧分发器；覆盖 moe.lolosia.keel.api.web.WebRequestComponent, moe.lolosia.keel.api.web.Component。 */
internal class WebRequestComponentImplDispatcher(
    private val impl: moe.lolosia.keel.api.worker.WebRequestComponentImpl,
) : PublicApiDispatcher {

    override val typeId: String = "moe.lolosia.keel.api.web.WebRequestComponent"

    override suspend fun dispatch(
        handle: Long,
        method: String,
        payload: ByteArray,
        callerModule: Long,
        overrides: Set<String>,
    ): ByteArray = when (method) {
        "request" -> {
            ServiceWire.encode(serializer<moe.lolosia.keel.api.web.HttpRequest>(), impl.request())
        }

        "pathParameters" -> {
            ServiceWire.encode(serializer<kotlin.collections.Map<String, String>>(), impl.pathParameters())
        }

        "user" -> {
            ServiceWire.encode(serializer<moe.lolosia.keel.api.web.UserSnapshot?>(), impl.user())
        }

        "id" -> {
            ServiceWire.encode(serializer<String>(), impl.id())
        }

        "destroy" -> {
            impl.destroy()
            ByteArray(0)
        }

        else -> throw IllegalArgumentException("WebRequestComponentImpl 没有方法 $method")
    }
}
