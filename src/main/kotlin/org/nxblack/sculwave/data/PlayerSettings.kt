package org.nxblack.echoWave.data

import kotlinx.serialization.Serializable
import java.util.UUID
@Serializable
data class PlayerSettings(
    var muted: Boolean = false,
    var muteAll: Boolean = false,
    var groupOnly: Boolean = false,
    var groupId: String? = null
)
