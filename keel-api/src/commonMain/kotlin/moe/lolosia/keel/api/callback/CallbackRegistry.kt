package moe.lolosia.keel.api.callback

/**
 * 模块内的回调表。
 *
 * - 调用方：`register` 拿到数字 id，随参数发给远端；远端只保存 id。
 * - 被调方：`invoker` 拿到一个绑定到「调用方模块 + 回调 id」的发送器，
 *   由生成的分发器包装成用户接口里的函数参数。
 *
 * 插件 disable 时由运行时统一注销剩余回调。
 */
interface CallbackRegistry {

    fun register(descriptor: CallbackDescriptor): Long

    fun unregister(id: Long)

    /**
     * @param ownerModule 调用方模块的 ABI id（不透明，生成代码从请求事件里取）
     * @param callbackId 调用方注册时拿到的 id
     */
    fun invoker(ownerModule: Long, callbackId: Long): CallbackInvoker
}
