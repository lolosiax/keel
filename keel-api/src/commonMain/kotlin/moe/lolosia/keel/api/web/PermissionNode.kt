package moe.lolosia.keel.api.web

import kotlinx.serialization.Serializable

/**
 * 一个可授予的权限节点，由 `@Permission` 在构建期扫出。
 *
 * Host 聚合成权限目录供管理界面使用；同一节点被多个插件声明是允许的（OR 语义）。
 *
 * @property node 规范化的节点名（`a.b.c`）
 * @property title 管理界面显示名
 * @property controller 声明它的控制器
 * @property function 声明它的函数
 */
@Serializable
data class PermissionNode(
    val node: String,
    val title: String = node,
    val controller: String = "",
    val function: String = "",
)
