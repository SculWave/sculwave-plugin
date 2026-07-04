package org.nxblack.echoWave.data

import org.bukkit.Bukkit

object BackendState {
    var instanceId = "standalone"
    var backendId = Bukkit.getServer().name
    var roomCode = ""
    var authToken = ""

}