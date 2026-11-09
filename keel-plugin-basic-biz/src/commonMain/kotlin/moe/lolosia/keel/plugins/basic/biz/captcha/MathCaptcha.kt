package moe.lolosia.keel.plugins.basic.biz.captcha

import kotlin.io.encoding.Base64
import kotlin.random.Random

/**
 * 自研算式验证码：随机两个个位数相加，把 `a+b` 画成 PNG（base64）。
 *
 * 不依赖任何图片库：5×7 点阵字自己画，PNG 用**未压缩的 deflate 存储块**拼出来，
 * 体积略大但对验证码这种小图完全可以接受；K/N 上没有 Deflater。
 *
 * @property answer 算式结果，校验时与用户输入比较
 * @property imageBase64 PNG 的 base64（前端直接拼 `data:image/png;base64,`）
 */
internal class MathCaptcha(val answer: String, val imageBase64: String)

private const val SCALE = 5
private const val MARGIN = 8
private const val CHAR_WIDTH = 5
private const val CHAR_HEIGHT = 7
private const val CHAR_GAP = 1

/** 生成一道加法题与对应的 PNG。 */
internal fun createMathCaptcha(random: Random = Random.Default): MathCaptcha {
    val left = random.nextInt(1, 10)
    val right = random.nextInt(1, 10)
    val text = "$left+$right"
    val pixels = draw(text)
    return MathCaptcha(
        answer = (left + right).toString(),
        imageBase64 = Base64.encode(encodePng(pixels.width, pixels.height, pixels.rgba)),
    )
}

private class Image(val width: Int, val height: Int, val rgba: ByteArray)

/** 白底黑字；未覆盖的像素保持白色。 */
private fun draw(text: String): Image {
    val width = MARGIN * 2 + text.length * CHAR_WIDTH * SCALE + (text.length - 1) * CHAR_GAP * SCALE
    val height = MARGIN * 2 + CHAR_HEIGHT * SCALE
    val rgba = ByteArray(width * height * 4) { 0xFF.toByte() }

    fun set(x: Int, y: Int) {
        if (x !in 0 until width || y !in 0 until height) return
        val offset = (y * width + x) * 4
        rgba[offset] = 0
        rgba[offset + 1] = 0
        rgba[offset + 2] = 0
        rgba[offset + 3] = 0xFF.toByte()
    }

    text.forEachIndexed { index, ch ->
        val glyph = glyphOf(ch) ?: return@forEachIndexed
        val originX = MARGIN + index * (CHAR_WIDTH + CHAR_GAP) * SCALE
        val originY = MARGIN
        for (row in 0 until CHAR_HEIGHT) {
            val bits = glyph[row]
            for (column in 0 until CHAR_WIDTH) {
                if (bits and (1 shl (CHAR_WIDTH - 1 - column)) == 0) continue
                for (dy in 0 until SCALE) {
                    for (dx in 0 until SCALE) {
                        set(originX + column * SCALE + dx, originY + row * SCALE + dy)
                    }
                }
            }
        }
    }
    return Image(width, height, rgba)
}

// ——— PNG ———

private fun encodePng(width: Int, height: Int, rgba: ByteArray): ByteArray {
    val raw = ByteArray((width * 4 + 1) * height)
    var cursor = 0
    for (y in 0 until height) {
        raw[cursor++] = 0 // 过滤类型 None
        val rowStart = y * width * 4
        rgba.copyInto(raw, cursor, rowStart, rowStart + width * 4)
        cursor += width * 4
    }

    val output = ArrayList<Byte>(raw.size + 128)
    output.addAll(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).toList())

    val header = ByteArray(13)
    writeInt(header, 0, width)
    writeInt(header, 4, height)
    header[8] = 8 // bit depth
    header[9] = 6 // color type RGBA
    header[10] = 0
    header[11] = 0
    header[12] = 0
    appendChunk(output, "IHDR", header)

    appendChunk(output, "IDAT", zlibStored(raw))
    appendChunk(output, "IEND", ByteArray(0))

    return output.toByteArray()
}

private fun appendChunk(output: MutableList<Byte>, type: String, data: ByteArray) {
    val length = ByteArray(4)
    writeInt(length, 0, data.size)
    output.addAll(length.toList())

    val typeBytes = type.encodeToByteArray()
    output.addAll(typeBytes.toList())
    output.addAll(data.toList())

    val crcInput = typeBytes + data
    val crc = ByteArray(4)
    writeInt(crc, 0, crc32(crcInput))
    output.addAll(crc.toList())
}

/** zlib 包装 + 未压缩 deflate 块（BTYPE=00），免去在 K/N 上实现压缩。 */
private fun zlibStored(raw: ByteArray): ByteArray {
    val output = ArrayList<Byte>(raw.size + raw.size / 65535 * 5 + 16)
    output.add(0x78)
    output.add(0x01)

    var offset = 0
    do {
        val size = minOf(65535, raw.size - offset)
        val final = offset + size >= raw.size
        output.add(if (final) 1 else 0)
        output.add((size and 0xFF).toByte())
        output.add((size shr 8 and 0xFF).toByte())
        val inverse = size xor 0xFFFF
        output.add((inverse and 0xFF).toByte())
        output.add((inverse shr 8 and 0xFF).toByte())
        for (index in 0 until size) output.add(raw[offset + index])
        offset += size
    } while (offset < raw.size)

    val adler = ByteArray(4)
    writeInt(adler, 0, adler32(raw))
    output.addAll(adler.toList())
    return output.toByteArray()
}

private fun writeInt(target: ByteArray, offset: Int, value: Int) {
    target[offset] = (value shr 24 and 0xFF).toByte()
    target[offset + 1] = (value shr 16 and 0xFF).toByte()
    target[offset + 2] = (value shr 8 and 0xFF).toByte()
    target[offset + 3] = (value and 0xFF).toByte()
}

private val CRC_TABLE: IntArray by lazy {
    IntArray(256) { index ->
        var value = index
        repeat(8) {
            value = if (value and 1 != 0) 0xEDB88320.toInt() xor (value ushr 1) else value ushr 1
        }
        value
    }
}

private fun crc32(data: ByteArray): Int {
    var crc = -1
    for (byte in data) {
        crc = CRC_TABLE[(crc xor byte.toInt()) and 0xFF] xor (crc ushr 8)
    }
    return crc.inv()
}

private fun adler32(data: ByteArray): Int {
    var a = 1
    var b = 0
    for (byte in data) {
        a = (a + (byte.toInt() and 0xFF)) % 65521
        b = (b + a) % 65521
    }
    return (b shl 16) or a
}

// ——— 5×7 点阵字 ———

private fun glyphOf(char: Char): IntArray? = when (char) {
    '0' -> glyph(0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110)
    '1' -> glyph(0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110)
    '2' -> glyph(0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111)
    '3' -> glyph(0b11111, 0b00010, 0b00100, 0b00010, 0b00001, 0b10001, 0b01110)
    '4' -> glyph(0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010)
    '5' -> glyph(0b11111, 0b10000, 0b11110, 0b00001, 0b00001, 0b10001, 0b01110)
    '6' -> glyph(0b00110, 0b01000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110)
    '7' -> glyph(0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000)
    '8' -> glyph(0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110)
    '9' -> glyph(0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00010, 0b01100)
    '+' -> glyph(0b00000, 0b00100, 0b00100, 0b11111, 0b00100, 0b00100, 0b00000)
    else -> null
}

private fun glyph(vararg rows: Int): IntArray = rows
