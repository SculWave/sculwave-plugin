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

    override fun getPluginId(): String = "sculwave"

    override fun registerEvents(registration: EventRegistration) {
        registration.registerEvent(VoicechatServerStartedEvent::class.java) { onServerStarted(it) }
        registration.registerEvent(VoicechatServerStoppedEvent::class.java) { onServerStopped() }
        registration.registerEvent(PlayerConnectedEvent::class.java) { track(it.connection) }
        registration.registerEvent(PlayerDisconnectedEvent::class.java) { bridge.connected.remove(it.playerUuid) }
        registration.registerEvent(PlayerStateChangedEvent::class.java) { onStateChanged(it) }
        registration.registerEvent(MicrophonePacketEvent::class.java) { onMicrophone(it) }
    }

    // ---------------------------------------------------------------- lifecycle

    private fun onServerStarted(event: VoicechatServerStartedEvent) {
        val serverApi = event.voicechat
        api = serverApi

        val category = serverApi.volumeCategoryBuilder()
            .setId(CATEGORY)
            .setName("SculWave")
            .setDescription("Players talking through the SculWave app")
            .build()
        serverApi.registerVolumeCategory(category)

        host.logger.info("[SVC] Simple Voice Chat server started - app audio can now be played to SVC players.")
    }

    private fun onServerStopped() {
        api = null
        speakers.clear()
        bridge.connected.clear()
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

        if (connection.isConnected && !connection.isDisabled) {
            bridge.connected[player.uniqueId] = player.name
        } else {
            bridge.connected.remove(player.uniqueId)
        }
    }

    private fun onStateChanged(event: PlayerStateChangedEvent) {
        if (event.isDisconnected || event.isDisabled) {
            bridge.connected.remove(event.playerUuid)
        } else {
            track(event.connection)
        }
    }

    // ---------------------------------------------------- SVC player -> app players

    private fun onMicrophone(event: MicrophonePacketEvent) {
        val sender = event.senderConnection ?: return

        // Audio inside an SVC group is private to that group - never leak it to proximity.
        if (sender.isInGroup) return

        val packet = event.packet
        if (packet.isWhispering && !host.forwardWhispers()) return

        val player = sender.player.player as? Player ?: return
        if (!host.bridgeEnabled() || !host.canSpeak(player)) return

        val opus = packet.opusEncodedData
        if (opus == null || opus.isEmpty()) return

        host.sendMicAudio(player.name, opus)
    }

    // ---------------------------------------------------- app player -> SVC players

    override fun play(speaker: String, opus: ByteArray) {
        val serverApi = api ?: return
        val player = host.findPlayer(speaker) ?: return

        if (!host.canSpeak(player)) return

        // The speaker also runs Simple Voice Chat: everyone already hears them natively.
        if (bridge.connected.containsKey(player.uniqueId)) return

        var channels = speakers[player.uniqueId]
        if (channels == null || channels.proximity.isClosed) {
            channels = createChannels(serverApi, player) ?: return
            speakers[player.uniqueId] = channels
        }

        if (opus.isEmpty()) {
            channels.flush()
            return
        }

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
