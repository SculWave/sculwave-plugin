package org.nxblack.echoWave.data

import kotlinx.serialization.Serializable

@Serializable
data class BackendRegisterRequest(
    val instanceId: String,
    val backendId: String,
)