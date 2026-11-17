package moe.lolosia.keel.plugins.dog

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.annotation.PublicApi
import moe.lolosia.keel.api.share.PublicApiDirectory
import moe.lolosia.keel.api.share.SharedProxy
import moe.lolosia.keel.plugins.animal.Animal
import moe.lolosia.keel.plugins.animal.AnimalKey
import moe.lolosia.keel.plugins.animal.Callable

/**
 * 实例方提供的可构造对象：初始化时自己创建 [Dog] 并登记，`invoke` 返回它。
 *
 * 构造与初始化都经 Host 状态机：构造完处于 Creating，只有创建者能调 [initialization]，
 * 成功后才允许 [invoke]。
 */
@PublicApi
open class SpawnAnimal(private val api: PublicApiDirectory? = null) : Callable, SharedProxy {

    override var sharedHandle: Long = 0L
        protected set

    private var animal: Animal? = null

    fun attachHandle(handle: Long) {
        sharedHandle = handle
    }

    /** 创建并登记 [Dog]；远端参数与本地资源在这一步准备。 */
    @PublicApi
    open suspend fun initialization() {
        val directory = api ?: error("SpawnAnimal 没有绑定 PublicApiDirectory")
        val dog = Dog()
        val ref = directory.register(
            instance = dog,
            key = AnimalKey,
            dispatcher = DogDispatcher(dog),
            overrides = setOf("bark"),
        )
        dog.sharedHandle = ref.handle
        animal = dog
        Log.info("SpawnAnimal.initialization: Dog(handle=${ref.handle})")
    }

    override suspend fun invoke(): Animal {
        Log.info("SpawnAnimal.invoke: 返回已初始化的 Dog")
        return animal ?: error("SpawnAnimal 尚未 initialization")
    }
}
