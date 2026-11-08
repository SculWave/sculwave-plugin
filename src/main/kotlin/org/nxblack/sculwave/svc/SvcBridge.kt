package org.nxblack.sculwave.svc

import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Counters that show where audio is flowing or being dropped. See /vc svcstatus. */
class SvcStats {
    val micForwarded = AtomicLong()
    val micSkippedGroup = AtomicLong()
    val micSkippedWhisper = AtomicLong()
    val micSkippedBlocked = AtomicLong()
    val micNotSent = AtomicLong()
    val playbackReceived = AtomicLong()
    val playbackPlayed = AtomicLong()
    val playbackNoPlayer = AtomicLong()
    val playbackBlocked = AtomicLong()
    val playbackNativeSpeaker = AtomicLong()
    val playbackNoChannel = AtomicLong()

    fun lines(): List<String> = listOf(
        "SVC -> app: forwarded=${micForwarded.get()} notSent(backend offline)=${micNotSent.get()} " +
            "skipped[group=${micSkippedGroup.get()} whisper=${micSkippedWhisper.get()} muted/banned/off=${micSkippedBlocked.get()}]",
        "app -> SVC: received=${playbackReceived.get()} played=${playbackPlayed.get()} " +
            "dropped[unknownPlayer=${playbackNoPlayer.get()} muted/banned=${playbackBlocked.get()} " +
            "speakerHasSvc=${playbackNativeSpeaker.get()} noChannel=${playbackNoChannel.get()}]"
    )
}

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

    /** Last time each SVC player's microphone sent audio (drives the HUD's "speaking" state). */
    val lastMicAt = ConcurrentHashMap<UUID, Long>()

    /** SVC players who muted their own microphone in Simple Voice Chat. */
    val micMuted: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    fun isSpeaking(uuid: UUID): Boolean =
        System.currentTimeMillis() - (lastMicAt[uuid] ?: 0L) < 400L

    val stats = SvcStats()

    @Volatile
    private var lastStatsLog = System.currentTimeMillis()

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
    fun connectedNames(): List<String> {
        logStatsIfDue()
        return if (active && host.bridgeEnabled()) connected.values.toList() else emptyList()
    }

    /** Lines for /vc svcstatus. */
    fun statusLines(): List<String> = buildList {
        add("Hooked into Simple Voice Chat: $active, bridge enabled: ${host.bridgeEnabled()}")
        add("SVC players (${connected.size}): ${connected.values.joinToString().ifEmpty { "none" }}")
        addAll(stats.lines())
    }

    // Called once a second from the heartbeat; summarises traffic once a minute when debug is on.
    private fun logStatsIfDue() {
        val now = System.currentTimeMillis()
        if (now - lastStatsLog < 60_000) return
        lastStatsLog = now
        if (active) statusLines().forEach { host.debug(it) }
    }

    /** Audio from an app player arrived from the backend. */
    fun onPlayback(speaker: String, opus: ByteArray) {
        if (!host.bridgeEnabled()) return
        playback?.play(speaker, opus)
    }

    fun onQuit(player: Player) {
        connected.remove(player.uniqueId)
        lastMicAt.remove(player.uniqueId)
        micMuted.remove(player.uniqueId)
        playback?.speakerLeft(player.uniqueId)
    }
}
