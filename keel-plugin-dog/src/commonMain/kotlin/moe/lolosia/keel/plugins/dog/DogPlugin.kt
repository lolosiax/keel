package moe.lolosia.keel.plugins.dog

import kotlinx.coroutines.launch
import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.Plugin
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.annotation.KeelPluginMain
import moe.lolosia.keel.plugins.animal.CALLABLE_TYPE_ID
import moe.lolosia.keel.plugins.animal.animalPlugin

/**
 * 实例方示例插件：注册 `SpawnAnimal` 的构造器，按远程构造流程拿回代理，
 * 初始化后再把回调交给声明方。
 *
 * `requires: ["animal"]` 保证声明方先启用；`onEnable` 不是 `suspend`，
 * 跨插件调用放进 `ctx.scope`，在 Worker 线程上执行。
 */
@KeelPluginMain
class DogPlugin : Plugin {

    private lateinit var context: PluginContext

    override fun onLoad(context: PluginContext) {
        this.context = context
        // 类型声明由生成的 KeelPluginPublicApi 完成；这里只登记构造器
        context.publicApi.constructor(
            key = SpawnAnimalKey,
            factory = { handle -> SpawnAnimal(context.publicApi).also { it.attachHandle(handle) } },
            dispatcher = { SpawnAnimalDispatcher(it, context.publicApi) },
            interfaces = listOf(CALLABLE_TYPE_ID),
            initialization = true,
        )
        Log.info("loaded")
    }

    override fun onEnable() {
        context.scope.launch {
            val callback = context.publicApi.createInstance(SpawnAnimalKey) { SpawnAnimalProxy(it) }
            Log.info("createInstance 返回 SpawnAnimal(handle=${callback.sharedHandle})，state=Creating")

            // 状态机：初始化之前除 initialization 外的调用都应被 Host 拒绝
            try {
                callback.invoke()
                Log.warn("未初始化调用 invoke 竟然成功了")
            } catch (e: Throwable) {
                Log.info("未初始化调用 invoke 被拒绝：${e.message}")
            }

            callback.initialization()
            Log.info("initialization 完成，准备注册到 AnimalPlugin")

            context.animalPlugin.register(callback)
            Log.info("注册完成")
        }
    }

    override fun onDisable() {
        Log.info("disabled")
    }
}
