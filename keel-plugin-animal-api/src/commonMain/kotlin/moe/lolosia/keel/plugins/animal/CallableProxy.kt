package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge
import moe.lolosia.keel.api.share.SharedRef

/**
 * 远端 `Callable` 在调用方运行时里的代理。
 *
 * `invoke` 的返回值按引用传：解码出句柄后包成 [AnimalProxy]。
 */
class CallableProxy(private val handle: Long) : Callable {

    override suspend fun invoke(): Animal {
        val ref = ServiceWire.decode(
            SharedRef.serializer(),
            PublicApiBridge.call(handle, CALLABLE_TYPE_ID, "invoke"),
        )
        return AnimalProxy(ref.handle)
    }
}
