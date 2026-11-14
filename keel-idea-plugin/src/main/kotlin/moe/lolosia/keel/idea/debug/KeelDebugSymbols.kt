package moe.lolosia.keel.idea.debug

import java.io.File

/**
 * `.keel-debug` 位置表：把 IDE 的「文件 + 行」映射成运行期的 `locationId`，
 * 也能把 `SUSPENDED` 报回来的 `locationId` 反查成源码位置。
 *
 * 文件由编译器插件与插桩同趟生成，首行是 `#moduleId buildId arch`，
 * 其后每行 `locationId functionId file line package class function kind`。
 *
 * `keel-api` 里的桥接帧不参与编译期插桩，运行期用负数 id 通过 `NAMES` 帧注册，
 * 这里用 [addNamed] 收进同一张表。
 *
 * `locationId` 只在模块内唯一（Host 与每个插件各自从 1 开始），所以反查必须带模块 id。
 */
class KeelDebugSymbols private constructor(
    private val entries: MutableList<Entry>,
    private val byModule: MutableMap<String, MutableMap<Int, Entry>>,
) {

    /** 位置表里的一行。 */
    class Entry(
        val moduleId: String,
        val locationId: Int,
        val functionId: Int,
        val file: String,
        val line: Int,
        val packageName: String,
        val className: String,
        val functionName: String,
        val kind: String,
    ) {
        /**
         * 与日志同形的 `包.类`；顶层函数没有类名，退化到包名。
         *
         * 位置表里的 `class` 列已经是全限定名（`LogSiteResolver.fullClassName`），
         * 这里不能再拼一次包名。
         */
        val owner: String
            get() = className.ifEmpty { packageName }

        val function: String?
            get() = functionName.ifEmpty { null }
    }

    val isEmpty: Boolean get() = synchronized(this) { entries.isEmpty() }

    val size: Int get() = synchronized(this) { entries.size }

    /** 运行期注册的桥接帧（`keel-api` 的那几层）。 */
    fun addNamed(moduleId: String, locationId: Int, owner: String, file: String, line: Int) {
        val entry = Entry(moduleId, locationId, 0, file, line, "", owner, "", KIND_BRIDGE)
        synchronized(this) {
            entries += entry
            byModule.getOrPut(moduleId) { HashMap() }[locationId] = entry
        }
    }

    /** 运行期上报的「模块 + locationId」→ 位置；位置表里没有时返回 null。 */
    fun location(moduleId: String, locationId: Int): Entry? = synchronized(this) {
        byModule[moduleId]?.get(locationId)
            ?: byModule.values.singleOrNull()?.get(locationId)
    }

    /** 断点落在空行 / 注释上时下滑到最近的可执行位置，与 JVM 调试器行为一致。 */
    fun resolve(absolutePath: String, line: Int): Entry? {
        val path = absolutePath.replace('\\', '/')
        val candidates = synchronized(this) {
            entries.filter { it.kind == KIND_LINE && path.endsWith("/" + it.file.trimStart('/')) }
        }
        if (candidates.isEmpty()) return null
        return candidates.filter { it.line >= line }.minByOrNull { it.line }
            ?: candidates.maxByOrNull { it.line }
    }

    companion object {
        private const val KIND_LINE = "line"
        private const val KIND_BRIDGE = "bridge"

        fun load(debugDir: File): KeelDebugSymbols {
            val entries = ArrayList<Entry>()
            if (debugDir.isDirectory) {
                debugDir.listFiles { file -> file.isFile && file.name.endsWith(".keel-debug") }
                    .orEmpty()
                    .sortedBy { it.name }
                    .forEach { parse(it, entries) }
            }
            val byModule = entries.groupBy { it.moduleId }
                .mapValues { (_, list) -> list.associateBy { it.locationId }.toMutableMap() }
                .toMutableMap()
            return KeelDebugSymbols(entries, byModule)
        }

        private fun parse(file: File, into: MutableList<Entry>) {
            var moduleId = ""
            file.forEachLine { raw ->
                if (raw.isBlank()) return@forEachLine
                if (raw.startsWith("#")) {
                    moduleId = raw.removePrefix("#").split('\t').firstOrNull().orEmpty()
                    return@forEachLine
                }
                val cells = raw.split('\t')
                if (cells.size < 8) return@forEachLine
                val id = cells[0].toIntOrNull() ?: return@forEachLine
                val line = cells[3].toIntOrNull() ?: return@forEachLine
                into += Entry(
                    moduleId = moduleId,
                    locationId = id,
                    functionId = cells[1].toIntOrNull() ?: 0,
                    file = cells[2].replace('\\', '/'),
                    line = line,
                    packageName = cells[4],
                    className = cells[5],
                    functionName = cells[6],
                    kind = cells[7],
                )
            }
        }
    }
}
