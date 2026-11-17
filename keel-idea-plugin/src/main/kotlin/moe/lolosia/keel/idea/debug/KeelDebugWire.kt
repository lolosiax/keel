package moe.lolosia.keel.idea.debug

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Keel 调试通道的帧格式。
 *
 * 帧 = `type:u8 + length:i32 + payload`；payload 是 UTF-8、字段用 `\t` 分隔。
 * 运行期侧（`keel-api` 的 `DebugWire`）必须逐字节一致，改这里就要同步改那边。
 */
object KeelDebugWire {

    const val HELLO = 1
    const val BREAKPOINTS = 2
    const val SUSPENDED = 3
    const val STACK = 4
    const val RESUME = 5
    const val GET_STACK = 6
    const val DISCONNECT = 7

    /** 旧版命中瞬间的参数快照，先于 `SUSPENDED` 到达（只有名字 + 字符串）。 */
    const val VALUES = 8

    /** 运行期注册的桥接帧：`locationId  name  file  line`。 */
    const val NAMES = 9

    /** 等待方快照：`correlation  module  callers`，用于跨 image 拼栈。 */
    const val WAITING = 10

    /** IDE → agent：展开一个句柄，payload 是 `ref`。 */
    const val GET_MEMBERS = 11

    /** agent → IDE：一帧的变量快照 `locationId  count` + 每行 `name type preview ref`。 */
    const val FRAME_VALUES = 12

    /** agent → IDE：句柄的子成员 `ref  stopSeq  typeName  count` + 每行 `name type preview childRef`。 */
    const val MEMBERS = 13

    private const val MAX_PAYLOAD = 1 shl 20

    class Frame(val type: Int, val payload: String) {
        val fields: List<String> get() = payload.split('\t')
    }

    /** 读到对端关闭时返回 null，调用方据此结束读循环。 */
    fun read(input: DataInputStream): Frame? {
        val type = try {
            input.readUnsignedByte()
        } catch (e: EOFException) {
            return null
        }
        val length = input.readInt()
        require(length in 0..MAX_PAYLOAD) { "调试帧过大：$length" }
        val bytes = ByteArray(length)
        input.readFully(bytes)
        return Frame(type, bytes.decodeToString())
    }

    fun write(output: DataOutputStream, type: Int, payload: String) {
        val bytes = payload.encodeToByteArray()
        require(bytes.size <= MAX_PAYLOAD) { "调试帧过大：${bytes.size}" }
        output.writeByte(type)
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }
}
