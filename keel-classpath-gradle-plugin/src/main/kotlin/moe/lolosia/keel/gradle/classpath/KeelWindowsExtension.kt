package moe.lolosia.keel.gradle.classpath

/**
 * Windows 可执行文件的 PE 版本信息（只对 mingw 目标生效）。
 *
 * 由 `moe.lolosia.keel.classpath` 生成 `.rc`、经 windres 编译后链入 exe；
 * 任务管理器、资源管理器「属性 → 详细信息」读到的都是这里的字段。
 *
 * 版本号不在这里配 —— 直接取工程的 `version`。
 */
open class KeelWindowsExtension {

    /** 产品名；留空取工程名。 */
    var productName: String = ""

    /** 文件说明；留空取 [productName]。任务管理器进程列表显示的就是它。 */
    var fileDescription: String = ""

    /** 公司名；留空则不写该字段。 */
    var companyName: String = ""

    /** 版权；留空则不写该字段。 */
    var copyright: String = ""

    /** 应用图标（`.ico`）；留空用插件内置的 Keel 默认图标。 */
    var icon: String = ""
}
