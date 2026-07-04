package org.nxblack.echoWave.data

import kotlinx.serialization.Serializable

@Serializable
data class BackendHeartbeatRequest(
    val instanceId: String,
    val backendId: String,
    val token: String,
    val timestamp: Long,
    val players: List<PositionPayload>
)
