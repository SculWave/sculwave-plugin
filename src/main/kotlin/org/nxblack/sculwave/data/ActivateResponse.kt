package org.nxblack.echoWave.data

import kotlinx.serialization.Serializable

@Serializable
data class ActivateResponse(
    val success: Boolean,
    val backendId: String,
    val instanceId: String,
    val heartbeatInterval: Long
)
