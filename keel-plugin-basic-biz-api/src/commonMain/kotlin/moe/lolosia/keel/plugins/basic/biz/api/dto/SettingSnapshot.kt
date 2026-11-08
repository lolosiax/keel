package moe.lolosia.keel.plugins.basic.biz.api.dto

import kotlinx.serialization.Serializable

/**
 * 运行时设置的快照（面板上改、立刻生效、重启后仍在）。
 *
 * 配置文件里的同名项是初值，这里存的是改过之后的值。
 *
 * @property allowHiddenFiles 是否允许客户端访问隐藏项（`.` 开头，以及 `lost+found`）
 */
@Serializable
data class SettingSnapshot(
    val allowHiddenFiles: Boolean = false,
)
