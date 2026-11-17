// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge

/** `moe.lolosia.keel.plugins.hello.HelloPlugin` 在消费方运行时里的代理。 */
class HelloPluginProxy(
    private val proxyHandle: Long,
) : moe.lolosia.keel.plugins.hello.HelloPlugin {
}
