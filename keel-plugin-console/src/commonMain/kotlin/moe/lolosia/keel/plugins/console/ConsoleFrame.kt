package moe.lolosia.keel.plugins.console

/**
 * helper 桥帧编解码。
 *
 * 帧头 12 字节小端：`magic("KCN1") + version(u16) + type(u16) + length(u32)`。
 * 载荷是原始终端字节或控制参数；未知 type 由接收方忽略，未知 version 断开。
 */
internal object ConsoleFrame {

    const val VERSION: Int = 1
    const val HEADER_SIZE: Int = 12
    const val MAX_PAYLOAD: Int = 1 shl 20

    const val HELLO: Int = 0x01
    const val OUT: Int = 0x02
    const val IN: Int = 0x03
    const val RESIZE: Int = 0x04
    const val TITLE: Int = 0x05
    const val MODE: Int = 0x06
    const val CLOSE: Int = 0x07
    const val PING: Int = 0x08
    const val PONG: Int = 0x09

    private const val MAGIC0 = 0x4B
    private const val MAGIC1 = 0x43
    private const val MAGIC2 = 0x4E
    private const val MAGIC3 = 0x31

    fun encode(type: Int, payload: ByteArray): ByteArray {
        val out = ByteArray(HEADER_SIZE + payload.size)
        out[0] = MAGIC0.toByte()
        out[1] = MAGIC1.toByte()
        out[2] = MAGIC2.toByte()
        out[3] = MAGIC3.toByte()
        putShort(out, 4, VERSION)
        putShort(out, 6, type)
        putInt(out, 8, payload.size)
        payload.copyInto(out, HEADER_SIZE)
        return out
    }

    fun magicOf(header: ByteArray): Boolean =
        header.size >= HEADER_SIZE &&
            header[0] == MAGIC0.toByte() &&
            header[1] == MAGIC1.toByte() &&
            header[2] == MAGIC2.toByte() &&
            header[3] == MAGIC3.toByte()

    fun versionOf(header: ByteArray): Int = shortAt(header, 4)

    fun typeOf(header: ByteArray): Int = shortAt(header, 6)

    fun lengthOf(header: ByteArray): Int = intAt(header, 8)

    private fun putShort(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun putInt(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun shortAt(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)

    private fun intAt(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)
}
