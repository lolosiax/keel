package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.annotation.Singleton

/**
 * [HelloApi] 的实现；无参构造器，KSP 生成的注册表会实例化并登记为本插件的公开单例。
 */
@Singleton
class HelloServiceImpl : HelloApi {

    override fun greet(name: String): String = "hello, $name"

    override suspend fun echo(text: String): String = "echo: $text"
}
