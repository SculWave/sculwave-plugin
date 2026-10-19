package org.nxblack.echoWave.data

import kotlinx.serialization.Serializable

@Serializable
data class UpdateResponse(
    val success: Boolean? = false,
    val error: String? = null
)