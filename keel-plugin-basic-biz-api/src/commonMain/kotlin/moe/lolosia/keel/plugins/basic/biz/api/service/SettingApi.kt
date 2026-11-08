package moe.lolosia.keel.plugins.basic.biz.api.service

import moe.lolosia.keel.api.annotation.*
import moe.lolosia.keel.plugins.basic.biz.api.dto.*

/**
 * 运行时设置。
 *
 * 写入立即生效并持久化；托管在 basic-biz 内，因为它与账户生命周期同属一个插件。
 */
@ModuleService("basic.biz.setting")
interface SettingApi {

    /** 当前设置快照。 */
    suspend fun snapshot(): SettingSnapshot

    /** 开关「允许访问隐藏项」，返回写入后的快照。 */
    suspend fun setAllowHiddenFiles(allow: Boolean): SettingSnapshot
}
