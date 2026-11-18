package moe.lolosia.keel.idea.nav

import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Keel 自己的 gutter 图标：Keel 标记 + 右下角方向箭头。
 *
 * 与 Kotlin 原生图标（绿色下箭头 / 蓝色上箭头）区分，避免在同一行出现两个
 * 长得一样、行为却不同的图标。
 */
object KeelIcons {

    /** 指向真实实现（下箭头）。 */
    val Implemented: Icon = IconLoader.getIcon("/icons/keel-implemented.svg", KeelIcons::class.java)

    /** 指向父声明的 impl（上箭头）。 */
    val Overridden: Icon = IconLoader.getIcon("/icons/keel-overridden.svg", KeelIcons::class.java)
}
