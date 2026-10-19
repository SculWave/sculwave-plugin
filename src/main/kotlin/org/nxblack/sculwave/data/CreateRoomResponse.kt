package org.nxblack.echoWave.data

import kotlinx.serialization.Serializable

@Serializable
data class CreateRoomResponse(
    val success: Boolean,
    val roomCode: String,
    val token: String
)
