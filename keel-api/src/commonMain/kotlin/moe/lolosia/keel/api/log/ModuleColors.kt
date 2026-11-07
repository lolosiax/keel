package moe.lolosia.keel.api.log

/**
 * 模块名 → 作者自定义颜色。
 *
 * Host 读取 `plugin.json` 的 `color` 后在这里登记；[LogLineFormatter] 优先用它，
 * 没有登记时才回落到 [Ansi.hashModule] 的哈希色。
 *
 * 登记使用 copy-on-write，读取端拿到的是不可变快照；仍应在启动插件 / 输出日志前完成登记。
 */
object ModuleColors {

    private var colors: Map<String, String> = emptyMap()

    /** 登记 `#RRGGBB` / `#RGB`；颜色非法时返回 false，不覆盖已有登记。 */
    fun register(module: String, hex: String): Boolean {
        val style = Ansi.trueColor(hex) ?: return false
        colors = colors + (module to style)
        return true
    }

    fun resolve(module: String): String? = colors[module]
}
