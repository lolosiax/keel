package moe.lolosia.keel.plugins.basic.biz

import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.annotation.ModuleServiceProvider
import moe.lolosia.keel.plugins.basic.biz.api.dto.SettingSnapshot
import moe.lolosia.keel.plugins.basic.biz.api.service.SettingApi

/** [SettingApi] 的实现：读写插件私有配置，Host 负责落盘并保留注释。 */
@ModuleServiceProvider
class SettingApiImpl(private val context: PluginContext) : SettingApi {

    private val settings = BasicBizSettings(context)

    override suspend fun snapshot(): SettingSnapshot = settings.snapshot()

    override suspend fun setAllowHiddenFiles(allow: Boolean): SettingSnapshot =
        settings.setAllowHidden(allow)
}
