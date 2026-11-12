package moe.lolosia.keel.gradle.classpath

import java.io.File

/** 一条嵌入 Windows PE 的自定义资源；[file] 是相对 `.rc` 的文件名。 */
data class KeelEmbeddedResource(
    val id: String,
    val type: String,
    val file: String,
)

/**
 * Windows 版本资源 / 图标 / 自定义资源的脚本生成与工具链定位。
 *
 * `moe.lolosia.keel.classpath` 与 `moe.lolosia.keel.plugin` 的单 exe 产出共用这里，
 * 保证两边生成的 `VERSIONINFO`、图标与 `windres` 参数完全一致。
 */
object KeelWindowsResources {

    const val ICON_FILE = "keel-icon.ico"

    const val DEFAULT_ICON_RESOURCE = "/moe/lolosia/keel/gradle/classpath/keel-icon.ico"

    /**
     * 生成 `windres` 用的 `.rc`。
     *
     * 版本号取 Gradle 的 `version`（FILEVERSION 只认 4 段数字，`-SNAPSHOT` 这类后缀会被丢掉），
     * 文件内容按 UTF-8 写，windres 侧用 `--codepage=65001` 读。
     */
    fun buildScript(
        embedded: List<KeelEmbeddedResource>,
        icon: String,
        version: String,
        productName: String,
        fileDescription: String,
        companyName: String,
        copyright: String,
        internalName: String,
        executableName: String,
    ): String {
        val quad = winVersionParts(version).joinToString(",")
        val quote = { value: String -> value.replace("\"", "'") }

        return buildString {
            appendLine("// 由 Gradle 生成，不要手改：版本号取工程 version，名称来自 windows { … }。")
            appendLine("// 文件本身是 UTF-8，windres 以 --codepage=65001 读取。")
            if (embedded.isNotEmpty()) {
                appendLine()
                embedded.forEach {
                    appendLine("// 内嵌资源 ${it.type}；运行期按 id / type 查找。")
                    appendLine("${it.id}  ${it.type}  \"${it.file}\"")
                }
            }
            appendLine()
            appendLine("// 应用图标；未配置 icon 时用插件内置的默认图标。")
            appendLine("1 ICON \"$icon\"")
            appendLine()
            appendLine("1 VERSIONINFO")
            appendLine("FILEVERSION $quad")
            appendLine("PRODUCTVERSION $quad")
            appendLine("FILEFLAGSMASK 0x3fL")
            appendLine("FILEFLAGS 0x0L")
            appendLine("FILEOS 0x40004L")
            appendLine("FILETYPE 0x1L")
            appendLine("FILESUBTYPE 0x0L")
            appendLine("BEGIN")
            appendLine("  BLOCK \"StringFileInfo\"")
            appendLine("  BEGIN")
            appendLine("    BLOCK \"040904b0\"")
            appendLine("    BEGIN")
            if (companyName.isNotBlank()) {
                appendLine("      VALUE \"CompanyName\", \"${quote(companyName)}\"")
            }
            appendLine("      VALUE \"FileDescription\", \"${quote(fileDescription)}\"")
            appendLine("      VALUE \"FileVersion\", \"${quote(version)}\"")
            appendLine("      VALUE \"InternalName\", \"${quote(internalName)}\"")
            if (copyright.isNotBlank()) {
                appendLine("      VALUE \"LegalCopyright\", \"${quote(copyright)}\"")
            }
            appendLine("      VALUE \"OriginalFilename\", \"${quote(executableName)}\"")
            appendLine("      VALUE \"ProductName\", \"${quote(productName)}\"")
            appendLine("      VALUE \"ProductVersion\", \"${quote(version)}\"")
            appendLine("    END")
            appendLine("  END")
            appendLine("  BLOCK \"VarFileInfo\"")
            appendLine("  BEGIN")
            appendLine("    VALUE \"Translation\", 0x409, 1200")
            appendLine("  END")
            appendLine("END")
        }
    }

    /** `1.0.0-SNAPSHOT` → `[1, 0, 0, 0]`：FILEVERSION 只接受 4 段数字。 */
    fun winVersionParts(version: String): List<Int> {
        val numeric = version.substringBefore('-').substringBefore('+')
        val parts = numeric.split('.').map { segment -> segment.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        return (parts + List(4) { 0 }).take(4)
    }

    /** 把插件内置的默认图标释放到目标文件，供 `windres` 读取。 */
    fun extractDefaultIcon(target: File) {
        val stream = KeelWindowsResources::class.java.getResourceAsStream(DEFAULT_ICON_RESOURCE)
            ?: error("插件 jar 内找不到默认图标：$DEFAULT_ICON_RESOURCE")
        stream.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
    }

    /** Kotlin/Native 自带的 mingw 工具链目录；首次 mingwX64 链接后才会存在。 */
    fun konanMingwBinDir(): File {
        val konanDataDir = System.getenv("KONAN_DATA_DIR")
            ?: "${System.getProperty("user.home")}/.konan"
        val binDir = File(konanDataDir, "dependencies/msys2-mingw-w64-x86_64-2/bin")
        check(binDir.isDirectory) {
            "未找到 Kotlin/Native 自带的 mingw 工具链：$binDir\n" +
                "请先执行一次 mingwX64 链接（会自动下载工具链），或检查 KONAN_DATA_DIR。"
        }
        return binDir
    }
}
