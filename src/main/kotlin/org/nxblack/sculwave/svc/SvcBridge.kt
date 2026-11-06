package org.nxblack.sculwave.svc

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Connects SculWave (LiveKit / app players) with Simple Voice Chat (mod players).
 *
 *  - SVC player speaks  -> Opus frame -> SculWave backend -> LiveKit participant -> app players
 *  - App player speaks  -> Opus frame <- SculWave backend <- LiveKit            -> SVC audio channel
 *
 * This class only holds state and routes calls. Everything that touches the Simple Voice Chat API
 * lives in [SculWaveVoicechatPlugin], which is only loaded when Simple Voice Chat is present.
 */
class SvcBridge(private val host: SvcHost) {

    /** Players currently connected to Simple Voice Chat (uuid -> name). Thread-safe. */
    val connected = ConcurrentHashMap<UUID, String>()

    @Volatile
    private var playback: SvcPlayback? = null

    val active: Boolean
        get() = playback != null

    fun register(plugin: JavaPlugin) {
        if (plugin.server.pluginManager.getPlugin("voicechat") == null) {
            host.logger.info("[SVC] Simple Voice Chat not found - bridge inactive.")
            return
        }

        try {
            playback = SvcHook.register(plugin, host, this)
            if (playback != null) {
                host.logger.info("[SVC] Simple Voice Chat found - bridge registered.")
            } else {
                host.logger.warning("[SVC] Simple Voice Chat is installed but its service is unavailable.")
            }
        } catch (e: LinkageError) {
            // Incompatible Simple Voice Chat API version. Never take SculWave down because of it.
            host.logger.warning("[SVC] Could not hook into Simple Voice Chat (${e.javaClass.simpleName}: ${e.message}).")
        } catch (e: Exception) {
            host.logger.warning("[SVC] Could not hook into Simple Voice Chat: ${e.message}")
        }
    }

    fun shutdown() {
        playback?.shutdown()
        playback = null
        connected.clear()
    }

    /** Names reported to the backend with every heartbeat. */
    fun connectedNames(): List<String> =
        if (active && host.bridgeEnabled()) connected.values.toList() else emptyList()

    /** Audio from an app player arrived from the backend. */
    fun onPlayback(speaker: String, opus: ByteArray) {
        if (!host.bridgeEnabled()) return
        playback?.play(speaker, opus)
    }

    fun onQuit(player: Player) {
        connected.remove(player.uniqueId)
        playback?.speakerLeft(player.uniqueId)
    }
}
