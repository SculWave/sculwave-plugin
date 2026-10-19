package org.nxblack.sculwave.data

import kotlinx.serialization.Serializable

@Serializable
data class CreateRoomResponse(
    val success: Boolean,
    val roomCode: String,
    val token: String
)
