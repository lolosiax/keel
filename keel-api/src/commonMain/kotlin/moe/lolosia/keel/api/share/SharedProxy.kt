package moe.lolosia.keel.api.share

/**
 * 共享对象的本地视图。
 *
 * 代理持有远端句柄，可以直接参与「按引用传参」：`handleOf(proxy)` 不需要它先登记。
 */
interface SharedProxy {

    /** Host 分配的句柄；本地实例登记前为 0。 */
    val sharedHandle: Long
}
