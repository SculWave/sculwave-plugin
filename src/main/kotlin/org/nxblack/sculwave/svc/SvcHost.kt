package org.nxblack.sculwave.svc

import org.bukkit.entity.Player
import java.util.UUID
import java.util.logging.Logger

/**
 * What the Simple Voice Chat bridge needs from SculWave.
 *
 * This file (and [SvcBridge]) deliberately import nothing from the Simple Voice Chat API,
 * so SculWave still loads normally on servers that do not have Simple Voice Chat installed.
 */
interface SvcHost {
    val logger: Logger

    /** Logged only when `debug: true` in config.yml. */
    fun debug(message: String)

    /** `svc-bridge.enabled` and `voice-enabled` are both true. Cheap, called per audio packet. */
    fun bridgeEnabled(): Boolean

    /** Whether SVC "whisper" packets may be forwarded to app players (off by default). */
    fun forwardWhispers(): Boolean

    /** Proximity range in blocks for app players heard by SVC players. 0 = SVC's own distance. */
    fun proximityDistance(): Double

    /** Not voice-banned and not muted with /vm. */
    fun canSpeak(player: Player): Boolean

    /** Not voice-banned and has not turned voice off for themselves. */
    fun canListen(player: Player): Boolean

    /** True when the player is already in the SculWave app (so they must not hear bridged audio twice). */
    fun hasAppVoice(player: Player): Boolean

    /** SculWave group the player is in, or null. */
    fun groupOf(player: Player): String?

    /** Player chose to hear their group only. */
    fun groupOnly(player: Player): Boolean

    /** Case-insensitive lookup of an online player (never prefix matching). */
    fun findPlayer(name: String): Player?

    /** Send one Opus frame from an SVC player to the SculWave backend. */
    fun sendMicAudio(gamertag: String, opus: ByteArray)
}

/** Plays audio from SculWave app players to SVC players. Implemented by the SVC plugin class. */
interface SvcPlayback {
    /** [opus] is one 20 ms mono 48 kHz Opus frame. An empty array marks the end of an utterance. */
    fun play(speaker: String, opus: ByteArray)

    fun speakerLeft(uuid: UUID)

    fun shutdown()
}
