package org.nxblack.sculwave.data

import kotlinx.serialization.Serializable

@Serializable
data class PlayerSettings(
    var muted: Boolean = false,
    var muteAll: Boolean = false,
    var groupOnly: Boolean = false,
    var groupId: String? = null
)
