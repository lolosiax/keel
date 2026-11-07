package moe.lolosia.keel.api.log

/**
 * 一条异常诊断记录。
 *
 * @property createdSite 构造点 `类.函数:行`；未在插桩代码里构造时为 null
 * @property throwSites 抛出点，按发生顺序；可能包含多次重抛
 */
class ThrowTraceEntry(
    val createdSite: String?,
    val throwSites: List<String>,
)
