package org.nxblack.echoWave.data

import java.util.UUID

data class Group(
    var owner: UUID,
    val members: MutableSet<UUID> = mutableSetOf(),
    val joinCode: String
)