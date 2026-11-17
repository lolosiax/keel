// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.animal

import kotlinx.serialization.builtins.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge
import moe.lolosia.keel.api.share.SharedRef

/** `moe.lolosia.keel.plugins.animal.Callable` 在消费方运行时里的代理。 */
class CallableProxy(
    private val proxyHandle: Long,
) : moe.lolosia.keel.plugins.animal.Callable {

    override suspend fun invoke(): moe.lolosia.keel.plugins.animal.Animal {
        val ref = ServiceWire.decode(SharedRef.serializer(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.animal.Callable", "invoke"))
        return moe.lolosia.keel.plugins.animal.AnimalProxy(ref.handle)
    }
}
