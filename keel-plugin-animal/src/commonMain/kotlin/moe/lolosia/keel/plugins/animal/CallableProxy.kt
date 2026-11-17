package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge
import moe.lolosia.keel.api.share.SharedRef

/**
 * 声明方运行时里的 `Callable` 代理：实例方传来的句柄在这里变成可调用的对象。
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
