package moe.lolosia.keel.plugins.basic.biz

import moe.lolosia.keel.api.web.*

/**
 * 菜单接口。
 *
 * 模板没有菜单表，与 backend 一样只回 `{"default": true}`：前端 `use-permission.ts` 的
 * `filterTagRouters` 按这个键放行内置路由，返回空表会让「用户中心」整块消失。
 */
@Controller("/api")
object MenuController {

    @POST("/menu/config")
    suspend fun config(): Map<String, Boolean> = mapOf("default" to true)
}
