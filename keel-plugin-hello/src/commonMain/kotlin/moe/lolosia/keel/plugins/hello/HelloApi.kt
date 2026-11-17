package moe.lolosia.keel.plugins.hello

import moe.lolosia.keel.api.annotation.PublicApi

/**
 * 跨模块服务的最小示例。
 *
 * 契约写在 impl 模块；api 工件里的镜像 / 代理 / Key / 访问器由 `keel-plugin-ksp` 生成。
 */
@PublicApi
interface HelloApi {

    fun greet(name: String): String

    suspend fun echo(text: String): String
}
