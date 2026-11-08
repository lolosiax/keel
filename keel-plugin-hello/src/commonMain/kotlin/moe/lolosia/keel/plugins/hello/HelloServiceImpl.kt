package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.annotation.ModuleServiceProvider

/**
 * [HelloApi] 的实现；无参构造器，KSP 直接用 `HelloServiceImpl()` 注册。
 */
@ModuleServiceProvider
class HelloServiceImpl : HelloApi {

    override fun greet(name: String): String = "hello, $name"

    override suspend fun echo(text: String): String = "echo: $text"
}
