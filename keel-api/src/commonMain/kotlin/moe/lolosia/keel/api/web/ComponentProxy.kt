// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.api.web

import kotlinx.serialization.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge

/** `moe.lolosia.keel.api.web.Component` 在消费方运行时里的代理。 */
class ComponentProxy(
    private val proxyHandle: Long,
) : moe.lolosia.keel.api.web.Component {

    override var context: moe.lolosia.keel.api.web.Context? = null

    override suspend fun id(): String {
        return ServiceWire.decode(serializer<String>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.api.web.Component", "id", ByteArray(0)))
    }

    override suspend fun destroy(): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.api.web.Component", "destroy", ByteArray(0))
    }
}
