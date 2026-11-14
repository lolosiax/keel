package moe.lolosia.keel.logger

import java.io.File

/**
 * `.keel-debug` 位置表。
 *
 * 与插桩同一趟 pass 生成：插桩分配 `locationId`，这里同步记一行，
 * 保证 ID 与「文件 / 行 / 函数」严格对齐。TSV 格式见 `doc/debug-design.md`：
 *
 * ```
 * #moduleId  buildId  arch
 * locationId  functionId  file  line  package  class  function  kind
 * ```
 */
internal class DebugLocationTable(
    private val moduleId: String,
    private val buildId: String,
) {

    private var nextLocationId = 1
    private var nextFunctionId = 1
    private val rows = ArrayList<String>(1024)

    fun nextLocationId(): Int = nextLocationId++

    fun nextFunctionId(): Int = nextFunctionId++

    fun add(locationId: Int, functionId: Int, site: LogSiteInfo, kind: String) {
        rows += buildString(96) {
            append(locationId).append('\t')
            append(functionId).append('\t')
            append(site.file).append('\t')
            append(site.line).append('\t')
            append(site.packageName.orEmpty()).append('\t')
            append(site.className.orEmpty()).append('\t')
            append(site.functionName.orEmpty()).append('\t')
            append(kind)
        }
    }

    /** 写盘；一次插桩都没发生时不留空文件，免得 IDE 误以为这个模块有位置表。 */
    fun writeTo(file: File) {
        if (rows.isEmpty()) return
        file.parentFile?.mkdirs()
        file.writeText(
            buildString(64 + rows.size * 64) {
                append('#').append(moduleId).append('\t').append(buildId).append('\t').append(ARCH).append('\n')
                rows.forEach { append(it).append('\n') }
            }
        )
    }

    private companion object {
        const val ARCH = "native"
    }
}
