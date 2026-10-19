package org.nxblack.echoWave.data

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object PlayerData {
    val settings: ConcurrentHashMap<UUID, PlayerSettings> = ConcurrentHashMap<UUID, PlayerSettings>()
}
