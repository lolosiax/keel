package moe.lolosia.keel.plugins.basic.biz

import moe.lolosia.keel.api.PluginContext
import moe.lolosia.keel.api.config.*
import moe.lolosia.keel.plugins.basic.biz.api.dto.SettingSnapshot

/**
 * 插件私有配置 + 运行时设置。
 *
 * 设置面板改的是这份文件（`config/plugins/basic-biz.yaml`），Host 负责合并写回并保留注释；
 * 文件缺失 / 老版本字段不全时按 [BasicBizConfig] 的默认值兜底，不让插件启动失败。
 */
internal class BasicBizSettings(private val context: PluginContext) {

    fun load(): BasicBizConfig = runCatching { context.config.read<BasicBizConfig>() }
        .getOrDefault(BasicBizConfig())

    fun snapshot(): SettingSnapshot = SettingSnapshot(allowHiddenFiles = load().allowHiddenFiles)

    fun setAllowHidden(allow: Boolean): SettingSnapshot {
        val current = load()
        context.config.write(current.copy(allowHiddenFiles = allow))
        return SettingSnapshot(allowHiddenFiles = allow)
    }
}
