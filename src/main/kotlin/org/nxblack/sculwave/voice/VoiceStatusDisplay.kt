package org.nxblack.echoWave.voice

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.nxblack.echoWave.SculWave

class VoiceStatusDisplay(private val plugin: SculWave) {

    fun update(player: Player) {
        val state = plugin.voiceStateManager.get(player.name)

        val message = when {
            state == null || !state.connected ->
                Component.text("EchoWave • Not Connected", NamedTextColor.RED)
            state.muted ->
                Component.text("EchoWave • Muted", NamedTextColor.YELLOW)
            state.speaking ->
                Component.text("EchoWave • Speaking", NamedTextColor.GREEN)
            else ->
                Component.text("EchoWave • Connected", NamedTextColor.AQUA)
        }

        player.sendActionBar(message)
    }

    fun clear(player: Player) {
        player.sendActionBar(Component.empty())
    }
}