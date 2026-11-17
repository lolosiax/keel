package moe.lolosia.keel.plugins.animal

/**
 * 声明方提供的回调接口：调用时返回一个 `Animal` 引用。
 *
 * 实现方通常是别的插件；声明方拿到的是指向实现的句柄代理。
 */
interface Callable {

    suspend fun invoke(): Animal
}
