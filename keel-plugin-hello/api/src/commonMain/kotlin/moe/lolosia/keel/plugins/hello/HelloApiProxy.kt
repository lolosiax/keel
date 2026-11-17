// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.hello

import kotlinx.serialization.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge

/** `moe.lolosia.keel.plugins.hello.HelloApi` 在消费方运行时里的代理。 */
class HelloApiProxy(
    private val proxyHandle: Long,
) : moe.lolosia.keel.plugins.hello.HelloApi {

    override fun greet(name: String): String {
        return ServiceWire.decode(serializer<String>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.hello.HelloApi", "greet", ServiceWire.encode(serializer<String>(), name)))
    }

    override suspend fun echo(text: String): String {
        return ServiceWire.decode(serializer<String>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.hello.HelloApi", "echo", ServiceWire.encode(serializer<String>(), text)))
    }
}
