package org.nxblack.sculwave.svc

import de.maxhenkel.voicechat.api.VoicechatConnection
import de.maxhenkel.voicechat.api.VoicechatPlugin
import de.maxhenkel.voicechat.api.VoicechatServerApi
import de.maxhenkel.voicechat.api.ServerPlayer
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel
import de.maxhenkel.voicechat.api.audiochannel.StaticAudioChannel
import de.maxhenkel.voicechat.api.events.EventRegistration
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent
import de.maxhenkel.voicechat.api.events.PlayerConnectedEvent
import de.maxhenkel.voicechat.api.events.PlayerDisconnectedEvent
import de.maxhenkel.voicechat.api.events.PlayerStateChangedEvent
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent
import de.maxhenkel.voicechat.api.events.VoicechatServerStoppedEvent
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * SculWave's plugin for Simple Voice Chat.
 *
 * Inbound  (SVC -> app): every [MicrophonePacketEvent] is forwarded as raw Opus to the backend.
 * Outbound (app -> SVC): [play] pushes Opus frames of an app player into SVC audio channels that
 *                        are keyed by the speaker's real UUID, so SVC's per-player volume and mute
 *                        controls (and speaking indicators) work for app players too.
 */
class SculWaveVoicechatPlugin(
    private val host: SvcHost,
    private val bridge: SvcBridge,
) : VoicechatPlugin, SvcPlayback {

    companion object {
        const val CATEGORY = "sculwave_app"
        private const val TARGET_SYNC_MS = 1_000L
    }

    @Volatile
    private var api: VoicechatServerApi? = null

    private val speakers = ConcurrentHashMap<UUID, SpeakerChannels>()
    private val firstMicSeen = ConcurrentHashMap.newKeySet<UUID>()
    private val stats get() = bridge.stats

    private class SpeakerChannels(
        val proximity: EntityAudioChannel,
        val group: StaticAudioChannel,
    ) {
        @Volatile
        var targetsSyncedAt = 0L

        fun flush() {
            proximity.flush()
            group.flush()
        }
    }

    private fun muted(target: Any?): Boolean {
        if (target == null) return false
        return try {
            val type: Class<*> = if (target is VoicechatConnection) VoicechatConnection::class.java else PlayerStateChangedEvent::class.java
            (type.getMethod("isMuted").invoke(target) as? Boolean) ?: false
        } catch (e: Throwable) {
            false
        }
    }

    override fun getPluginId(): String = "sculwave"

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(VoicechatServerStartedEvent::class.java) { onServerStarted(it) }
        registration.registerEvent(VoicechatServerStoppedEvent::class.java) { onServerStopped() }
        registration.registerEvent(PlayerConnectedEvent::class.java) { markConnected(it.connection) }
        registration.registerEvent(PlayerDisconnectedEvent::class.java) { markGone(it.playerUuid, "disconnected") }
        registration.registerEvent(PlayerStateChangedEvent::class.java) { onStateChanged(it) }
        registration.registerEvent(MicrophonePacketEvent::class.java) { onMicrophone(it) }
    }

    // ---------------------------------------------------------------- lifecycle

    private fun onServerStarted(event: VoicechatServerStartedEvent) {
        val serverApi = event.voicechat
        api = serverApi

        // Simple Voice Chat limits the id and the name to 16 characters. A bad value breaks the
        // packet it sends to every joining player, so never let this fail the voice chat itself.
        try {
            val category = serverApi.volumeCategoryBuilder()
                .setId(CATEGORY)
                .setName("SculWave App")
                .setDescription("Players talking through the SculWave app")
                .build()
            serverApi.registerVolumeCategory(category)
        } catch (e: Exception) {
            host.logger.warning("[SVC] Could not register the volume category: ${e.message}")
        }

        host.logger.info("[SVC] Simple Voice Chat server started - app audio can now be played to SVC players.")
    }

    private fun onServerStopped() {
        api = null
        speakers.clear()
        bridge.connected.clear()
        host.logger.info("[SVC] Simple Voice Chat server stopped - bridge paused.")
    }

    override fun shutdown() {
        onServerStopped()
    }

    override fun speakerLeft(uuid: UUID) {
        speakers.remove(uuid)
    }

    // ------------------------------------------------- SVC players (who is connected)

    private fun track(connection: VoicechatConnection?) {
        if (connection == null) return
        val player = connection.player.player as? Player ?: return

        if (muted(connection)) bridge.micMuted.add(player.uniqueId) else bridge.micMuted.remove(player.uniqueId)

        if (connection.isConnected && !connection.isDisabled) {
            if (bridge.connected.put(player.uniqueId, player.name) == null) {
                host.logger.info("[SVC] ${player.name} is on Simple Voice Chat (${bridge.connected.size} total).")
            }
        } else {
            markGone(player.uniqueId, "voice disabled or disconnected")
        }
    }

    /** The event itself means the player is on voice chat, whatever the flags on the connection say. */
    private fun markConnected(connection: VoicechatConnection?) {
        val player = connection?.player?.player as? Player ?: return
        if (muted(connection)) bridge.micMuted.add(player.uniqueId) else bridge.micMuted.remove(player.uniqueId)
        if (bridge.connected.put(player.uniqueId, player.name) == null) {
            host.logger.info("[SVC] ${player.name} is on Simple Voice Chat (${bridge.connected.size} total).")
        }
    }

    private fun markGone(uuid: UUID, why: String) {
        bridge.lastMicAt.remove(uuid)
        bridge.micMuted.remove(uuid)
        val name = bridge.connected.remove(uuid) ?: return
        firstMicSeen.remove(uuid)
        host.logger.info("[SVC] $name left Simple Voice Chat ($why, ${bridge.connected.size} left).")
    }

    private fun onStateChanged(event: PlayerStateChangedEvent) {
        if (muted(event)) bridge.micMuted.add(event.playerUuid) else bridge.micMuted.remove(event.playerUuid)

        if (event.isDisconnected || event.isDisabled) {
            markGone(event.playerUuid, if (event.isDisconnected) "disconnected" else "disabled voice")
        } else {
            track(event.connection)
        }
    }

    // ---------------------------------------------------- SVC player -> app players

    private fun onMicrophone(event: MicrophonePacketEvent) {
        val sender = event.senderConnection ?: return

        // Any packet means this player is talking (even if it is not forwarded): HUD state.
        (sender.player.player as? Player)?.let {
            bridge.lastMicAt[it.uniqueId] = System.currentTimeMillis()
            // A voice packet proves the player is on voice chat, even if the connect event was missed.
            if (bridge.connected.put(it.uniqueId, it.name) == null) {
                host.logger.info("[SVC] ${it.name} is on Simple Voice Chat (detected from voice packet).")
            }
        }

        // Audio inside an SVC group is private to that group - never leak it to proximity.
        if (sender.isInGroup) {
            stats.micSkippedGroup.incrementAndGet()
            return
        }

        val packet = event.packet
        if (packet.isWhispering && !host.forwardWhispers()) {
            stats.micSkippedWhisper.incrementAndGet()
            return
        }

        val player = sender.player.player as? Player ?: return
        if (!host.bridgeEnabled() || !host.canSpeak(player)) {
            stats.micSkippedBlocked.incrementAndGet()
            return
        }

        val opus = packet.opusEncodedData
        if (opus == null || opus.isEmpty()) return

        if (firstMicSeen.add(player.uniqueId)) {
            host.logger.info("[SVC] First voice packet from ${player.name} - forwarding to the SculWave backend.")
        }

        stats.micForwarded.incrementAndGet()
        host.sendMicAudio(player.name, opus)
    }

    // ---------------------------------------------------- app player -> SVC players

    override fun play(speaker: String, opus: ByteArray) {
        stats.playbackReceived.incrementAndGet()

        val serverApi = api ?: return
        val player = host.findPlayer(speaker)
        if (player == null) {
            if (stats.playbackNoPlayer.incrementAndGet() == 1L) {
                host.logger.warning("[SVC] Got app audio for '$speaker' but no such player is online (name mismatch?).")
            }
            return
        }

        if (!host.canSpeak(player)) {
            stats.playbackBlocked.incrementAndGet()
            return
        }

        // The speaker also runs Simple Voice Chat: everyone already hears them natively.
        if (bridge.connected.containsKey(player.uniqueId)) {
            stats.playbackNativeSpeaker.incrementAndGet()
            return
        }

        var channels = speakers[player.uniqueId]
        if (channels == null || channels.proximity.isClosed) {
            channels = createChannels(serverApi, player)
            if (channels == null) {
                stats.playbackNoChannel.incrementAndGet()
                host.logger.warning("[SVC] Could not create audio channels for ${player.name}.")
                return
            }
            speakers[player.uniqueId] = channels
            host.logger.info("[SVC] ${player.name} (SculWave app) is now audible to Simple Voice Chat players.")
        }

        if (opus.isEmpty()) {
            channels.flush()
            return
        }

        stats.playbackPlayed.incrementAndGet()
        syncGroupTargets(serverApi, channels)
        channels.proximity.send(opus)
        channels.group.send(opus)
    }

    private fun createChannels(serverApi: VoicechatServerApi, speaker: Player): SpeakerChannels? {
        val entity = serverApi.fromEntity(speaker)
        val proximity = serverApi.createEntityAudioChannel(speaker.uniqueId, entity) ?: return null
        val group = serverApi.createStaticAudioChannel(speaker.uniqueId) ?: return null

        proximity.setCategory(CATEGORY)
        group.setCategory(CATEGORY)
        group.setBypassGroupIsolation(true)

        val distance = host.proximityDistance()
        if (distance > 0.0) {
            proximity.setDistance(distance.toFloat())
        }

        // Evaluated for every packet, so group changes and mutes apply immediately.
        proximity.setFilter { listener -> hearsProximity(speaker, listener) }
        group.setFilter { listener -> hearsGroup(speaker, listener) }

        return SpeakerChannels(proximity, group)
    }

    /**
     * A static channel only delivers to explicitly added targets, so keep every connected SVC
     * player registered. The per-packet filter then narrows that down to the speaker's group.
     */
    private fun syncGroupTargets(serverApi: VoicechatServerApi, channels: SpeakerChannels) {
        val now = System.currentTimeMillis()
        if (now - channels.targetsSyncedAt < TARGET_SYNC_MS) return
        channels.targetsSyncedAt = now

        for (uuid in bridge.connected.keys) {
            val connection = serverApi.getConnectionOf(uuid) ?: continue
            channels.group.addTarget(connection)
        }
    }

    private fun listenerOf(target: ServerPlayer, speaker: Player): Player? {
        val listener = target.player as? Player ?: return null
        if (listener.uniqueId == speaker.uniqueId) return null
        if (!bridge.connected.containsKey(listener.uniqueId)) return null
        if (!host.canListen(listener)) return null
        // Listener is also in the app: they hear app players there, not a second time through SVC.
        if (host.hasAppVoice(listener)) return null
        return listener
    }

    private fun hearsProximity(speaker: Player, target: ServerPlayer): Boolean {
        val listener = listenerOf(target, speaker) ?: return false
        if (host.groupOnly(listener)) return false

        // Group members are served by the group channel (any distance), not by proximity.
        val speakerGroup = host.groupOf(speaker)
        return speakerGroup == null || speakerGroup != host.groupOf(listener)
    }

    private fun hearsGroup(speaker: Player, target: ServerPlayer): Boolean {
        val listener = listenerOf(target, speaker) ?: return false
        val speakerGroup = host.groupOf(speaker) ?: return false
        return speakerGroup == host.groupOf(listener)
    }
}
