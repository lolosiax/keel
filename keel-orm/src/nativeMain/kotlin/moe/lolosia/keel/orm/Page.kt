package moe.lolosia.keel.orm

/** 分页结果；[limit] 为 null 表示查询没有设置页大小。 */
class Page<T>(
    val items: List<T>,
    val total: Long,
    val limit: Int?,
    val offset: Int,
) {

    val hasNext: Boolean get() = offset + items.size < total
}
