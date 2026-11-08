package moe.lolosia.keel.api.service

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

/**
 * 跨模块服务的 CBOR 编解码。
 *
 * 生成代码只跟这里打交道，不直接依赖 `kotlinx-serialization-cbor` 的具体用法；
 * 统一配置也保证调用方与提供者按同一份格式读写。
 */
@OptIn(ExperimentalSerializationApi::class)
object ServiceWire {

    private val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): ByteArray =
        cbor.encodeToByteArray(serializer, value)

    fun <T> decode(serializer: KSerializer<T>, bytes: ByteArray): T =
        cbor.decodeFromByteArray(serializer, bytes)

    fun encodeCall(call: ServiceCall): ByteArray = encode(ServiceCall.serializer(), call)

    fun decodeCall(bytes: ByteArray): ServiceCall = decode(ServiceCall.serializer(), bytes)

    fun encodeRegistration(registration: ServiceRegistration): ByteArray =
        encode(ServiceRegistration.serializer(), registration)

    fun decodeRegistration(bytes: ByteArray): ServiceRegistration =
        decode(ServiceRegistration.serializer(), bytes)
}
