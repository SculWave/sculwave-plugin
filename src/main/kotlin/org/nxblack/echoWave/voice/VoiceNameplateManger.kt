package org.nxblack.echoWave.voice

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Scoreboard
import org.bukkit.scoreboard.Team

class VoiceNameplateManager {

    private val scoreboard: Scoreboard
        get() = Bukkit.getScoreboardManager()!!.mainScoreboard

    private val speakingTeam: Team
        get() = getOrCreateTeam("echowave_speaking") {
            prefix(Component.text("● ", NamedTextColor.GREEN))
        }

    private val enabledTeam: Team
        get() = getOrCreateTeam("echowave_enabled") {
            prefix(Component.text("● ", NamedTextColor.YELLOW))
        }

    private val mutedTeam: Team
        get() = getOrCreateTeam("echowave_muted") {
            prefix(Component.empty())
        }

    private fun getOrCreateTeam(
        name: String,
        setup: Team.() -> Unit
    ): Team {
        return scoreboard.getTeam(name)
            ?: scoreboard.registerNewTeam(name).apply(setup)
    }

    fun update(
        player: Player,
        speaking: Boolean,
        muted: Boolean
    ) {
        removeFromAllTeams(player)

        when {
            muted -> mutedTeam.addEntry(player.name)

            speaking -> speakingTeam.addEntry(player.name)

            else -> enabledTeam.addEntry(player.name)
        }
    }

    fun updateSpeaking(
        player: Player,
        speaking: Boolean,
        muted: Boolean
    ) {
        update(player, speaking, muted)
    }

    fun updateMuted(
        player: Player,
        muted: Boolean,
        speaking: Boolean = false
    ) {
        update(player, speaking, muted)
    }

    private fun removeFromAllTeams(player: Player) {
        speakingTeam.removeEntry(player.name)
        enabledTeam.removeEntry(player.name)
        mutedTeam.removeEntry(player.name)
    }

    fun clear(player: Player) {
        removeFromAllTeams(player)
    }

    fun clearAll() {
        speakingTeam.entries.toList().forEach(speakingTeam::removeEntry)
        enabledTeam.entries.toList().forEach(enabledTeam::removeEntry)
        mutedTeam.entries.toList().forEach(mutedTeam::removeEntry)
    }
}
