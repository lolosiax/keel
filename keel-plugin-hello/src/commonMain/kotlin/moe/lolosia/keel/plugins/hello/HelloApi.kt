package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.annotation.ModuleService

/**
 * 跨模块服务的最小示例。
 *
 * Key / Client / 访问器由 `keel-plugin-ksp` 生成（同包的 `HelloApiKeel.kt`），
 * 服务端分发器与注册表由 `@ModuleServiceProvider` 触发生成。
 */
@ModuleService("hello")
interface HelloApi {

    fun greet(name: String): String

    suspend fun echo(text: String): String
}
