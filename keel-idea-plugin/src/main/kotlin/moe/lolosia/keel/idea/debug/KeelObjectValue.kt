package moe.lolosia.keel.idea.debug

import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace

/**
 * 变量栏里的一个只读节点。
 *
 * 有句柄时从 [KeelMemberSource] 懒加载子成员（一次一层）；没有句柄就是纯文本。
 * 运行期每个句柄只保存到下一次命中，过期请求由数据源丢弃。
 */
class KeelObjectValue(
    private val snapshot: KeelValueSnapshot,
    private val source: KeelMemberSource?,
) : XValue() {

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        val expandable = snapshot.ref != 0 && source != null
        node.setPresentation(null, snapshot.type.ifEmpty { null }, snapshot.preview, expandable)
    }

    override fun computeChildren(node: XCompositeNode) {
        val source = source
        val ref = snapshot.ref
        if (source == null || ref == 0) {
            super.computeChildren(node)
            return
        }
        source.requestMembers(ref) { members ->
            val children = XValueChildrenList(members.size)
            members.forEach { member ->
                children.add(member.name, KeelObjectValue(member, source))
            }
            node.addChildren(children, true)
        }
    }
}
