// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.animal

import kotlinx.serialization.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge

/** `moe.lolosia.keel.plugins.animal.Animal` 在消费方运行时里的代理。 */
class AnimalProxy(
    proxyHandle: Long,
) : moe.lolosia.keel.plugins.animal.Animal() {

    init {
        sharedHandle = proxyHandle
    }

    override fun bark(): Unit {
        PublicApiBridge.call(sharedHandle, "moe.lolosia.keel.plugins.animal.Animal", "bark", ByteArray(0))
    }

    override fun describe(): String {
        return ServiceWire.decode(serializer<String>(), PublicApiBridge.call(sharedHandle, "moe.lolosia.keel.plugins.animal.Animal", "describe", ByteArray(0)))
    }
}
