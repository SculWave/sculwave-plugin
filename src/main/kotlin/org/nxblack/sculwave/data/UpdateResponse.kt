package org.nxblack.sculwave.data

import kotlinx.serialization.Serializable

@Serializable
data class UpdateResponse(
    val success: Boolean? = false,
    val error: String? = null
)