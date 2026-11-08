package moe.lolosia.keel.api.host

import kotlinx.serialization.Serializable
import moe.lolosia.keel.api.web.PermissionNode

/** `HostSubjects.PERMISSION_CATALOG` 的应答。 */
@Serializable
data class PermissionCatalogReply(
    val nodes: List<PermissionNode> = emptyList(),
)
