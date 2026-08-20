package org.nxblack.echoWave.voice

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.util.Transformation
import org.joml.Vector3f
import org.nxblack.echoWave.EchoWave
import java.util.UUID

class VoiceIndicatorManager(
    private val plugin: EchoWave
) {
    private val indicators = mutableMapOf<UUID, TextDisplay>()
    private val updateScheduled = mutableSetOf<UUID>()

    fun update(player: Player) {
        val state = plugin.voiceStateManager.get(player.name)

        if (state == null || !state.connected) {
            clear(player)
            return
        }

        val text = when {
            state.speaking -> Component.text("●").color(NamedTextColor.GREEN)
            state.muted -> Component.text("●").color(NamedTextColor.GRAY)
            else -> Component.text("●").color(NamedTextColor.AQUA)
        }

        val indicator = indicators[player.uniqueId]

        if (indicator == null || !indicator.isValid) {
            create(player, text)
        } else {
            indicator.text(text)
        }
    }

    private fun create(player: Player, text: Component) {
        val location = player.location.clone().add(0.0, 2.6, 0.0)

        val indicator = player.world.spawn(
            location,
            TextDisplay::class.java
        )

        indicator.text(text)
        indicator.billboard = Display.Billboard.CENTER
        indicator.isSeeThrough = true
        indicator.shadowRadius = 0f
        indicator.shadowStrength = 0f
        indicator.backgroundColor = org.bukkit.Color.fromARGB(0, 0, 0, 0)
        indicator.transformation = Transformation(
            Vector3f(0f, 0f, 0f),
            indicator.transformation.leftRotation,
            Vector3f(0.7f, 0.7f, 0.7f),
            indicator.transformation.rightRotation
        )
        indicator.setGravity(false)

        indicators[player.uniqueId] = indicator
        scheduleFollow(player)
    }

    private fun scheduleFollow(player: Player) {
        val uuid = player.uniqueId

        if (!updateScheduled.add(uuid)) {
            return
        }

        player.scheduler.runAtFixedRate(
            plugin,
            { _ ->
                val indicator = indicators[uuid]

                if (!player.isOnline || indicator == null || !indicator.isValid) {
                    indicator?.remove()
                    indicators.remove(uuid)
                    updateScheduled.remove(uuid)
                    return@runAtFixedRate
                }

                indicator.teleport(
                    player.location.clone().add(0.0, 2.6, 0.0)
                )
            },
            null,
            1L,
            1L
        )
    }
    fun clear(player: Player) {
        val uuid = player.uniqueId
        indicators.remove(uuid)?.remove()
        updateScheduled.remove(uuid)
    }
    fun clearAll() {
        indicators.values.forEach { it.remove() }
        indicators.clear()
        updateScheduled.clear()
    }






}