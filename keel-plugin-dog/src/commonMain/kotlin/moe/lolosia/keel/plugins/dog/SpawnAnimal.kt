package moe.lolosia.keel.plugins.dog

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.plugins.animal.Animal
import moe.lolosia.keel.plugins.animal.Callable

/**
 * 实例方对 `Callable` 的实现：把外部创建的 [Dog] 交给声明方。
 *
 * 返回值按引用传，声明方拿到的是指向这个 Dog 的句柄代理。
 */
class SpawnAnimal(private val dog: Dog) : Callable {

    override suspend fun invoke(): Animal {
        Log.info("SpawnAnimal.invoke: 返回 Dog(handle=${dog.sharedHandle})")
        return dog
    }
}
