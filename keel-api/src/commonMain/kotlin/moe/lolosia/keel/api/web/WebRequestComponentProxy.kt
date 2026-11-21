// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.api.web

import kotlinx.serialization.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge

/** `moe.lolosia.keel.api.web.WebRequestComponent` 在消费方运行时里的代理。 */
class WebRequestComponentProxy(
    private val proxyHandle: Long,
) : moe.lolosia.keel.api.web.WebRequestComponent {

    override var context: moe.lolosia.keel.api.web.Context? = null

    override suspend fun request(): moe.lolosia.keel.api.web.HttpRequest {
        return ServiceWire.decode(serializer<moe.lolosia.keel.api.web.HttpRequest>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.api.web.WebRequestComponent", "request", ByteArray(0)))
    }

    override suspend fun pathParameters(): kotlin.collections.Map<String, String> {
        return ServiceWire.decode(serializer<kotlin.collections.Map<String, String>>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.api.web.WebRequestComponent", "pathParameters", ByteArray(0)))
    }

    override suspend fun user(): moe.lolosia.keel.api.web.UserSnapshot? {
        return ServiceWire.decode(serializer<moe.lolosia.keel.api.web.UserSnapshot?>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.api.web.WebRequestComponent", "user", ByteArray(0)))
    }

    override suspend fun id(): String {
        return ServiceWire.decode(serializer<String>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.api.web.WebRequestComponent", "id", ByteArray(0)))
    }

    override suspend fun destroy(): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.api.web.WebRequestComponent", "destroy", ByteArray(0))
    }
}
