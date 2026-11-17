package moe.lolosia.keel.plugins.dog

import kotlinx.coroutines.launch
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.Plugin
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.annotation.KeelPluginMain
import moe.lolosia.keel.plugins.animal.*

/**
 * 实例方示例插件：创建 [Dog] 与 [SpawnAnimal]，再把回调交给声明方。
 *
 * `requires: ["animal"]` 保证声明方先启用；`onEnable` 不是 `suspend`，
 * 跨插件调用放进 `ctx.scope`，在 Worker 线程上执行。
 */
@KeelPluginMain
class DogPlugin : Plugin {

    private lateinit var context: PluginContext

    override fun onLoad(context: PluginContext) {
        this.context = context
        Log.info("loaded")
    }

    override fun onEnable() {
        val dog = Dog()
        val dogRef = context.publicApi.register(
            instance = dog,
            key = AnimalKey,
            dispatcher = DogDispatcher(dog),
            overrides = setOf("bark"),
        )
        dog.attachHandle(dogRef.handle)

        val spawn = SpawnAnimal(dog)
        context.publicApi.register(
            instance = spawn,
            key = CallableKey,
            dispatcher = SpawnAnimalDispatcher(spawn, dogRef),
        )

        context.scope.launch {
            Log.info("准备把 SpawnAnimal(handle=${context.publicApi.handleOf(spawn)}) 注册到 AnimalPlugin")
            context.animalPlugin.register(spawn)
            Log.info("注册完成")
        }
    }

    override fun onDisable() {
        Log.info("disabled")
    }
}
