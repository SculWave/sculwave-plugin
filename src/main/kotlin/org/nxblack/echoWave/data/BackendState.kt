package org.nxblack.echoWave.data

import org.bukkit.Bukkit
import org.nxblack.echoWave.SculWave

object BackendState {

    var instanceId = "standalone"
    var backendId = Bukkit.getServer().name
    var authToken = ""

    var roomCode = ""

    fun setRoomCode(plugin: SculWave, code: String) {
        roomCode = code

        if (code.isBlank()) return

        if (Platform.isFolia) {
            Bukkit.getGlobalRegionScheduler().run(plugin) {
                sendRoomCode(plugin)
            }
        } else {
            Bukkit.getScheduler().runTask(plugin, Runnable {
                sendRoomCode(plugin)
            })
        }
    }

    private fun sendRoomCode(plugin: SculWave) {
        Bukkit.getOnlinePlayers().forEach { player ->
            player.sendMessage("§7Voice Room: §f$roomCode")
            player.sendMessage("§7Use this code in the SculWave app.")
        }
    }
}