package moe.lolosia.keel.plugins.basic.biz

import kotlinx.serialization.Serializable
import moe.lolosia.keel.api.web.*

/**
 * 运行时设置接口：面板上改、立刻生效、重启后仍在。
 *
 * 存取落在插件私有配置里，Host 负责合并写回并保留 YAML 注释。
 */
@Controller("/api")
object SettingController {

    @Serializable
    data class WebdavSettingDto(val allowHidden: Boolean = false)

    @Serializable
    data class AppSettingDto(val webdav: WebdavSettingDto = WebdavSettingDto())

    @POST("/setting/get")
    suspend fun get(ctx: Context): AppSettingDto =
        AppSettingDto(WebdavSettingDto(BasicBizSettings(ctx.plugin).load().allowHiddenFiles))

    @POST("/setting/webdav")
    suspend fun updateWebdav(ctx: Context, @Body req: WebdavSettingDto): AppSettingDto {
        BasicBizSettings(ctx.plugin).setAllowHidden(req.allowHidden)
        return AppSettingDto(req)
    }
}
