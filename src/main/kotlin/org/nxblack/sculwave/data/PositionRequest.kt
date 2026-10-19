package org.nxblack.echoWave.data

import kotlinx.serialization.Serializable


@Serializable
data class PositionRequest(
    val code: String,
    val token: String,
    val players: List<PositionPayload>
)