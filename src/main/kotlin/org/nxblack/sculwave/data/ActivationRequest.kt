package org.nxblack.sculwave.data


import kotlinx.serialization.Serializable

@Serializable
data class ActivateRequest(
    val activationKey: String,
    val backendId: String?,
    val serverName: String,
    val platform: String,
    val pluginVersion: String,
    val minecraftVersion: String,
    val entryPoint: Boolean,
    val entryPointPriority: Int?,
)