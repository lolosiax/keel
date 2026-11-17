package moe.lolosia.keel.plugins.animal

import moe.lolosia.keel.api.Log
import moe.lolosia.keel.api.Plugin
import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.annotation.KeelPluginMain
import moe.lolosia.keel.api.annotation.PublicApi
import moe.lolosia.keel.api.share.PublicApiKey
import moe.lolosia.keel.api.share.PublicApiType

/**
 * 声明方示例插件：导出 `Animal` / `Callable`，并把自己登记成共享单例。
 *
 * 另一个插件通过 `ctx.animalPlugin` 拿到本类的代理；`register` 在**本运行时**执行，
 * 覆盖方法再按句柄转回实例方。
 */
@PublicApi
@KeelPluginMain
class AnimalPlugin : Plugin {

    private lateinit var context: PluginContext

    override fun onLoad(context: PluginContext) {
        this.context = context
        context.publicApi.declare(
            types = listOf(
                PublicApiType(ANIMAL_TYPE_ID, hasBase = true),
                PublicApiType(CALLABLE_TYPE_ID, hasBase = false),
                PublicApiType(ANIMAL_PLUGIN_TYPE_ID, hasBase = false),
            ),
            handlers = listOf(AnimalDispatcher()),
        )
        val ref = context.publicApi.register(
            instance = this,
            key = PublicApiKey(ANIMAL_PLUGIN_TYPE_ID),
            dispatcher = AnimalPluginDispatcher(this),
            singleton = true,
        )
        Log.info("已登记为共享单例，handle=${ref.handle}")
    }

    /**
     * 接收回调并立即调用：`invoke` 在实例方执行，`bark` 在声明方与实例方各执行一半。
     */
    @PublicApi
    suspend fun register(callable: Callable) {
        Log.info("register: 收到 callable")
        val animal = callable.invoke()
        Log.info("register: invoke 返回 Animal，准备 bark")
        animal.bark()
        Log.info("register: describe=${animal.describe()}")
    }

    override fun onEnable() {
        Log.info("enabled")
    }

    override fun onDisable() {
        Log.info("disabled")
    }
}
