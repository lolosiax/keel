package moe.lolosia.keel.plugins.dog

import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge
import moe.lolosia.keel.api.share.SharedRef
import moe.lolosia.keel.plugins.animal.Animal
import moe.lolosia.keel.plugins.animal.AnimalProxy
import moe.lolosia.keel.plugins.animal.CALLABLE_TYPE_ID

/**
 * `SpawnAnimal` 在创建者运行时里的代理。
 *
 * [initialization] 与 [invoke] 都经 Host 转发到所有者；`invoke` 的返回值按句柄变成
 * 本地的 [AnimalProxy]。
 */
class SpawnAnimalProxy(private val handle: Long) : SpawnAnimal() {

    init {
        attachHandle(handle)
    }

    override suspend fun initialization() {
        PublicApiBridge.call(handle, SPAWN_ANIMAL_TYPE_ID, "initialization")
    }

    override suspend fun invoke(): Animal {
        val ref = ServiceWire.decode(
            SharedRef.serializer(),
            PublicApiBridge.call(handle, CALLABLE_TYPE_ID, "invoke"),
        )
        return AnimalProxy(ref.handle)
    }
}
