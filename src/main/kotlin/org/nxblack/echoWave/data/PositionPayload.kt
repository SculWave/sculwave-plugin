package org.nxblack.echoWave.data

import kotlinx.serialization.Serializable

@Serializable
data class PositionPayload(
    val gamertag: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val dimension: String,
    val isMuted: Boolean? = false,
    val muteAll: Boolean? = false,
    val groupOnly: Boolean? = false,
    val groupId: String? = null
)