package org.nxblack.echoWave.voice

import net.kyori.adventure.text.Component
import org.bukkit.Color
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.plugin.Plugin
import org.bukkit.util.Transformation
import org.joml.Vector3f
import java.util.UUID

class VoiceNameplateManager(
    private val plugin: Plugin
) {
    private val displays = mutableMapOf<UUID, TextDisplay>()
    private val scheduled = mutableSetOf<UUID>()

    fun update(viewer: Player, target: Player, speaking: Boolean) {
        if (viewer.uniqueId == target.uniqueId || !speaking) {
            clear(target)
            return
        }

        val uuid = target.uniqueId
        val text = Component.text("🎤 ${target.name}")

        val display = displays[uuid]

        if (display == null || !display.isValid) {
            create(target, text)
        } else {
            display.text(text)
        }
    }
    private fun create(player: Player, text: Component) {
        val display = player.world.spawn(
            player.location.clone().add(0.0, 2.7, 0.0),
            TextDisplay::class.java
        )

        display.text(text)
        display.billboard = Display.Billboard.CENTER
        display.isSeeThrough = true
        display.isShadowed = false
        display.backgroundColor = Color.fromARGB(0, 0, 0, 0)
        display.setGravity(false)
        display.transformation = Transformation(
            Vector3f(0f, 0f, 0f),
            display.transformation.leftRotation,
            Vector3f(0.65f, 0.65f, 0.65f),
            display.transformation.rightRotation
        )

        displays[player.uniqueId] = display
        schedule(player)
    }

    private fun schedule(player: Player) {
        val uuid = player.uniqueId

        if (!scheduled.add(uuid)) {
            return
        }

        player.scheduler.runAtFixedRate(
            plugin,
            { _ ->
                val display = displays[uuid]

                if (!player.isOnline || display == null || !display.isValid) {
                    display?.remove()
                    displays.remove(uuid)
                    scheduled.remove(uuid)
                    return@runAtFixedRate
                }

                display.teleport(
                    player.location.clone().add(0.0, 2.7, 0.0)
                )
            },
            null,
            1L,
            1L
        )
    }

    fun clear(player: Player) {
        val uuid = player.uniqueId
        displays.remove(uuid)?.remove()
        scheduled.remove(uuid)
    }

    fun clearAll() {
        displays.values.forEach { it.remove() }
        displays.clear()
        scheduled.clear()
    }
}