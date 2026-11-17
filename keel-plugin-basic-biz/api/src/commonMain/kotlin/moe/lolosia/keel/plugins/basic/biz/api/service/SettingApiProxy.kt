// 由 keel-plugin-ksp 生成，不要手改。
package moe.lolosia.keel.plugins.basic.biz.api.service

import kotlinx.serialization.serializer
import moe.lolosia.keel.api.service.ServiceWire
import moe.lolosia.keel.api.share.PublicApiBridge

/** `moe.lolosia.keel.plugins.basic.biz.api.service.SettingApi` 在消费方运行时里的代理。 */
class SettingApiProxy(
    private val proxyHandle: Long,
) : moe.lolosia.keel.plugins.basic.biz.api.service.SettingApi {

    override suspend fun snapshot(): moe.lolosia.keel.plugins.basic.biz.api.dto.SettingSnapshot {
        return ServiceWire.decode(serializer<moe.lolosia.keel.plugins.basic.biz.api.dto.SettingSnapshot>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.SettingApi", "snapshot", ByteArray(0)))
    }

    override suspend fun setAllowHiddenFiles(allow: Boolean): moe.lolosia.keel.plugins.basic.biz.api.dto.SettingSnapshot {
        return ServiceWire.decode(serializer<moe.lolosia.keel.plugins.basic.biz.api.dto.SettingSnapshot>(), PublicApiBridge.call(proxyHandle, "moe.lolosia.keel.plugins.basic.biz.api.service.SettingApi", "setAllowHiddenFiles", ServiceWire.encode(serializer<Boolean>(), allow)))
    }
}
