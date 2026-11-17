// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.animal

import kotlinx.serialization.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge
import moe.lolosia.keel.api.share.PublicApiDirectory
import moe.lolosia.keel.api.share.SharedRef

/** `moe.lolosia.keel.plugins.animal.AnimalPlugin` 在消费方运行时里的代理。 */
class AnimalPluginProxy(
    private val proxyHandle: Long, private val directory: PublicApiDirectory,
) : moe.lolosia.keel.plugins.animal.AnimalPlugin {

    override suspend fun register(callable: moe.lolosia.keel.plugins.animal.Callable): Unit {
        PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.animal.AnimalPlugin", "register", ServiceWire.encode(SharedRef.serializer(), SharedRef(directory.handleOf(callable))))
    }
}
