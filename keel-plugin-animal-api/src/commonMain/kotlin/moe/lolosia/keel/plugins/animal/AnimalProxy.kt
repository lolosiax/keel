package moe.lolosia.keel.plugins.animal

import kotlinx.serialization.builtins.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge

/**
 * 远端 `Animal` 在消费方运行时里的代理。
 *
 * 虚方法发普通调用（可能被声明方的影子转发给实例方）；
 * 与 [Animal] 的基类桩区分开，避免影子与镜像互相转发。
 */
class AnimalProxy(handle: Long) : Animal() {

    init {
        attachHandle(handle)
    }

    override fun bark() {
        PublicApiBridge.call(sharedHandle, ANIMAL_TYPE_ID, "bark")
    }

    override fun describe(): String =
        ServiceWire.decode(
            String.serializer(),
            PublicApiBridge.call(sharedHandle, ANIMAL_TYPE_ID, "describe"),
        )
}
