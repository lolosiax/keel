package moe.lolosia.keel.idea.debug

/**
 * 一个变量的只读快照：名字、声明类型、预览文本、可展开句柄。
 *
 * `ref == 0` 表示运行期没给它分配句柄（标量、字符串、没有成员的类），
 * 这时变量栏不显示展开箭头；非 0 时按需向 [KeelMemberSource] 要子成员。
 */
data class KeelValueSnapshot(
    val name: String,
    val type: String,
    val preview: String,
    val ref: Int,
)

/** 变量展开的数据源：`KeelDebugProcess` 在当前挂起帧的通道上发 `GET_MEMBERS`。 */
interface KeelMemberSource {

    /** 异步取某个句柄的子成员；失败或过期时回调空列表。 */
    fun requestMembers(ref: Int, callback: (List<KeelValueSnapshot>) -> Unit)
}
