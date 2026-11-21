/**
 * OpenBSD 风格 Blowfish 口令哈希（bcrypt）。
 *
 * 计算走 `crypt_blowfish`（C，静态链接，固定 -O2；见 `keel-thirdparty` 的 `bcrypt` 库），
 * Kotlin 侧只负责生成随机盐与常量时间比较：bcrypt 的 cost 10 是 2^10 轮 Eksblowfish，
 * 用 Kotlin 实现即便 release 也要数秒，debug 更是几十秒。
 *
 * 算法固定按 `$2y$` 语义计算（修正版，不做 sign-extension 安全偏移），
 * 与 jBCrypt / Spring Security 一致；输出前缀跟随调用方传入的 setting，
 * 因此既能校验库里既有的 `$2a$10$...`，也能原样比较 `$2b$` / `$2y$` 哈希。
 */
package moe.lolosia.keel.plugins.basic.biz.crypto

import kotlinx.cinterop.*
import moe.lolosia.keel.thirdparty.bcrypt.*
import kotlin.random.Random

object BCrypt {

    /** 盐长度（字节）。 */
    private const val SALT_LEN = 16

    /** `$2a$` + 2 位 cost + `$` + 22 位盐 + `\0`。 */
    private const val SALT_SIZE = 7 + 22 + 1

    /** setting(29) + 31 位哈希 + `\0`，crypt_blowfish 要求至少 7 + 22 + 31 + 1。 */
    private const val HASH_SIZE = 7 + 22 + 31 + 1

    /**
     * 用给定成本生成 salt（形如 `$2a$10$...` 的 29 字符串）。
     *
     * @param rounds 成本的 log2（有效范围 4..31，默认 10）
     */
    fun gensalt(rounds: Int = 10): String {
        require(rounds in 4..31) { "log_rounds out of range (4..31): $rounds" }
        val random = ByteArray(SALT_LEN).also { Random.Default.nextBytes(it) }
        return memScoped {
            val output = allocArray<ByteVar>(SALT_SIZE)
            val status = random.usePinned { pinned ->
                keel_bcrypt_gensalt(rounds, pinned.addressOf(0), random.size, output, SALT_SIZE)
            }
            check(status == 0) { "bcrypt gensalt failed (rounds=$rounds)" }
            output.toKString()
        }
    }

    /**
     * 用 salt 计算哈希（OpenBSD bcrypt 方案）。
     *
     * @param salt `gensalt` 的产物或已有的完整哈希（前 29 字节被用作 setting）
     * @throws IllegalArgumentException setting 不合法时
     */
    fun hashpw(password: String, salt: String): String = memScoped {
        // 统一按 $2y$（修正版）计算，避免 crypt_blowfish 对 $2a$ 的 sign-extension 安全偏移
        // 让非 ASCII 口令与 jBCrypt / Spring 算出的结果不一致。
        val minor = salt.getOrNull(2)?.takeIf {
            salt.length > 3 && salt[0] == '$' && salt[1] == '2' && it in 'a'..'z' && salt[3] == '$'
        }
        val setting = if (minor != null && minor != 'y') salt.replaceRange(2, 3, "y") else salt
        val output = allocArray<ByteVar>(HASH_SIZE)
        if (keel_bcrypt_hash(password, setting, output, HASH_SIZE) != 0) {
            throw IllegalArgumentException("Invalid bcrypt salt: $salt")
        }
        val hashed = output.toKString()
        if (minor != null) hashed.replaceRange(2, 3, minor.toString()) else hashed
    }

    /** 校验明文与既有哈希是否匹配；比较过程不提前退出。 */
    fun checkpw(plaintext: String, hashed: String): Boolean {
        val computed = try {
            hashpw(plaintext, hashed)
        } catch (e: Exception) {
            return false
        }
        val expected = hashed.encodeToByteArray()
        val actual = computed.encodeToByteArray()
        if (expected.size != actual.size) return false
        var diff = 0
        for (index in expected.indices) diff = diff or (expected[index].toInt() xor actual[index].toInt())
        return diff == 0
    }
}
