package org.nxblack.echoWave.voice

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.ScoreBoardTeamInfo
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.TeamMode
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * EchoWave voice indicator for Minecraft player nameplates.
 *
 * Does NOT use Bukkit Scoreboard/Team API.
 *
 * Voice states:
 *
 *   speaking       -> green ●
 *   enabled/silent -> yellow ●
 *   muted          -> no indicator
 *
 * Existing rank/team prefix, suffix, color, visibility,
 * collision and options are preserved.
 */
class VoiceNameplateManager {

    companion object {
        private const val ECHO_TEAM_PREFIX = "ew_voice_"

        private val GREEN_DOT =
            Component.text(
                "● ",
                NamedTextColor.GREEN
            )

        private val YELLOW_DOT =
            Component.text(
                "● ",
                NamedTextColor.YELLOW
            )
    }

    /**
     * Current voice state of every known player.
     */
    private val voiceStates =
        ConcurrentHashMap<UUID, VoiceState>()

    /**
     * Original scoreboard teams observed for each viewer.
     *
     * viewer UUID -> team name -> team state
     */
    private val teams =
        ConcurrentHashMap<UUID, MutableMap<String, TeamState>>()

    /**
     * Player -> original team mapping for every viewer.
     *
     * viewer UUID -> player name -> team name
     */
    private val playerTeams =
        ConcurrentHashMap<UUID, MutableMap<String, String>>()

    /**
     * Players currently represented by an EchoWave
     * client-side team.
     */
    private val decorated =
        ConcurrentHashMap.newKeySet<ViewerTarget>()

    private val listener =
        object : PacketListenerAbstract(
            PacketListenerPriority.HIGH
        ) {

            override fun onPacketSend(
                event: PacketSendEvent
            ) {
                if (
                    event.packetType !=
                    PacketType.Play.Server.TEAMS
                ) {
                    return
                }

                /*
                 * PacketEvents getPlayer() is generic.
                 * Explicitly request a Bukkit Player.
                 */
                val viewer =
                    event.getPlayer<Player>()
                        ?: return

                val packet =
                    WrapperPlayServerTeams(event)

                handleTeamPacket(
                    viewer = viewer,
                    packet = packet,
                    event = event
                )
            }
        }

    init {
        PacketEvents.getAPI()
            .eventManager
            .registerListener(listener)
    }

    /**
     * Update voice state.
     */
    fun update(
        player: Player,
        speaking: Boolean,
        muted: Boolean
    ) {
        voiceStates[player.uniqueId] =
            VoiceState(
                speaking = speaking,
                muted = muted
            )

        /*
         * Rebuild the player's visible nameplate
         * for every other player.
         */
        Bukkit.getOnlinePlayers().forEach { viewer ->

            if (
                viewer.uniqueId ==
                player.uniqueId
            ) {
                return@forEach
            }

            refresh(
                viewer = viewer,
                target = player
            )
        }
    }

    fun updateSpeaking(
        player: Player,
        speaking: Boolean,
        muted: Boolean
    ) {
        update(
            player = player,
            speaking = speaking,
            muted = muted
        )
    }

    fun updateMuted(
        player: Player,
        muted: Boolean,
        speaking: Boolean = false
    ) {
        update(
            player = player,
            speaking = speaking,
            muted = muted
        )
    }

    /**
     * Remove player state.
     */
    fun clear(
        player: Player
    ) {
        val targetId =
            player.uniqueId

        voiceStates.remove(targetId)

        decorated.removeIf {
            it.target == targetId
        }

        playerTeams.values.forEach {
            it.remove(player.name)
        }

        teams.values.forEach { viewerTeams ->
            viewerTeams.values.forEach { team ->
                team.players.remove(player.name)
            }
        }
    }

    /**
     * Shutdown.
     */
    fun clearAll() {
        voiceStates.clear()
        teams.clear()
        playerTeams.clear()
        decorated.clear()

        PacketEvents.getAPI()
            .eventManager
            .unregisterListener(listener)
    }

    /**
     * Process an outgoing vanilla scoreboard-team packet.
     */
    private fun handleTeamPacket(
        viewer: Player,
        packet: WrapperPlayServerTeams,
        event: PacketSendEvent
    ) {
        val teamName =
            packet.teamName

        /*
         * Ignore our own client-side teams.
         */
        if (
            teamName.startsWith(
                ECHO_TEAM_PREFIX
            )
        ) {
            return
        }

        val mode =
            packet.teamMode

        val viewerTeams =
            teams.computeIfAbsent(
                viewer.uniqueId
            ) {
                ConcurrentHashMap()
            }

        val viewerPlayerTeams =
            playerTeams.computeIfAbsent(
                viewer.uniqueId
            ) {
                ConcurrentHashMap()
            }

        when (mode) {

            TeamMode.CREATE -> {
                handleCreate(
                    viewer = viewer,
                    packet = packet,
                    event = event,
                    viewerTeams = viewerTeams,
                    viewerPlayerTeams = viewerPlayerTeams
                )
            }

            TeamMode.UPDATE -> {
                handleUpdate(
                    viewer = viewer,
                    packet = packet,
                    event = event,
                    viewerTeams = viewerTeams
                )
            }

            TeamMode.ADD_ENTITIES -> {
                handleAddEntities(
                    viewer = viewer,
                    packet = packet,
                    event = event,
                    viewerTeams = viewerTeams,
                    viewerPlayerTeams = viewerPlayerTeams
                )
            }

            TeamMode.REMOVE_ENTITIES -> {
                handleRemoveEntities(
                    viewer = viewer,
                    packet = packet,
                    event = event,
                    viewerTeams = viewerTeams,
                    viewerPlayerTeams = viewerPlayerTeams
                )
            }

            TeamMode.REMOVE -> {
                handleRemoveTeam(
                    viewer = viewer,
                    packet = packet,
                    event = event,
                    viewerTeams = viewerTeams,
                    viewerPlayerTeams = viewerPlayerTeams
                )
            }
        }
    }

    private fun handleCreate(
        viewer: Player,
        packet: WrapperPlayServerTeams,
        event: PacketSendEvent,
        viewerTeams: MutableMap<String, TeamState>,
        viewerPlayerTeams: MutableMap<String, String>
    ) {
        val info =
            packet.teamInfo.orElse(null)
                ?: return

        val players =
            packet.players.toList()

        val state =
            TeamState(
                teamName = packet.teamName,
                info = info,
                players =
                    ConcurrentHashMap.newKeySet<String>()
            )

        state.players.addAll(players)

        viewerTeams[packet.teamName] =
            state

        players.forEach { name ->
            viewerPlayerTeams[name] =
                packet.teamName
        }

        val decoratedPlayers =
            players.filter {
                shouldDecorate(
                    viewer,
                    it
                )
            }

        if (
            decoratedPlayers.isEmpty()
        ) {
            return
        }

        event.setCancelled(true)

        /*
         * Send the original team for everyone who isn't
         * being decorated.
         */
        val normalPlayers =
            players.filterNot {
                it in decoratedPlayers
            }

        if (
            normalPlayers.isNotEmpty()
        ) {
            send(
                viewer,
                WrapperPlayServerTeams(
                    packet.teamName,
                    TeamMode.CREATE,
                    info,
                    normalPlayers
                )
            )
        }

        /*
         * Give each voice player their own client-side team.
         */
        decoratedPlayers.forEach { name ->

            val target =
                Bukkit.getPlayerExact(name)
                    ?: return@forEach

            decorate(
                viewer = viewer,
                target = target,
                original = state
            )
        }
    }

    private fun handleUpdate(
        viewer: Player,
        packet: WrapperPlayServerTeams,
        event: PacketSendEvent,
        viewerTeams: MutableMap<String, TeamState>
    ) {
        val info =
            packet.teamInfo.orElse(null)
                ?: return

        val state =
            viewerTeams[packet.teamName]
                ?: return

        state.info = info

        val decoratedPlayers =
            state.players.filter {
                shouldDecorate(
                    viewer,
                    it
                )
            }

        if (
            decoratedPlayers.isEmpty()
        ) {
            return
        }

        event.setCancelled(true)

        /*
         * Send updated original team state.
         */
        val normalPlayers =
            state.players.filterNot {
                it in decoratedPlayers
            }

        /*
         * UPDATE does not contain entity names.
         * It changes the team itself.
         *
         * Send the normal team update first.
         */
        send(
            viewer,
            WrapperPlayServerTeams(
                packet.teamName,
                TeamMode.UPDATE,
                info,
                emptyList<String>()
            )
        )

        /*
         * Then update every EchoWave copy.
         */
        decoratedPlayers.forEach { name ->

            val target =
                Bukkit.getPlayerExact(name)
                    ?: return@forEach

            updateDecorated(
                viewer = viewer,
                target = target,
                original = state
            )
        }
    }

    private fun handleAddEntities(
        viewer: Player,
        packet: WrapperPlayServerTeams,
        event: PacketSendEvent,
        viewerTeams: MutableMap<String, TeamState>,
        viewerPlayerTeams: MutableMap<String, String>
    ) {
        val state =
            viewerTeams[packet.teamName]
                ?: return

        val incoming =
            packet.players.toList()

        state.players.addAll(
            incoming
        )

        incoming.forEach { name ->
            viewerPlayerTeams[name] =
                packet.teamName
        }

        val decoratedPlayers =
            incoming.filter {
                shouldDecorate(
                    viewer,
                    it
                )
            }

        if (
            decoratedPlayers.isEmpty()
        ) {
            return
        }

        event.setCancelled(true)

        val normalPlayers =
            incoming.filterNot {
                it in decoratedPlayers
            }

        if (
            normalPlayers.isNotEmpty()
        ) {
            send(
                viewer,
                WrapperPlayServerTeams(
                    packet.teamName,
                    TeamMode.ADD_ENTITIES,
                    null as ScoreBoardTeamInfo?,
                    normalPlayers
                )
            )
        }

        decoratedPlayers.forEach { name ->

            val target =
                Bukkit.getPlayerExact(name)
                    ?: return@forEach

            decorate(
                viewer = viewer,
                target = target,
                original = state
            )
        }
    }

    private fun handleRemoveEntities(
        viewer: Player,
        packet: WrapperPlayServerTeams,
        event: PacketSendEvent,
        viewerTeams: MutableMap<String, TeamState>,
        viewerPlayerTeams: MutableMap<String, String>
    ) {
        val state =
            viewerTeams[packet.teamName]
                ?: return

        val removed =
            packet.players.toList()

        removed.forEach { name ->

            state.players.remove(name)

            if (
                viewerPlayerTeams[name] ==
                packet.teamName
            ) {
                viewerPlayerTeams.remove(name)
            }
        }

        val decoratedPlayers =
            removed.filter {
                isDecorated(
                    viewer,
                    it
                )
            }

        if (
            decoratedPlayers.isEmpty()
        ) {
            return
        }

        event.setCancelled(true)

        val normalPlayers =
            removed.filterNot {
                it in decoratedPlayers
            }

        if (
            normalPlayers.isNotEmpty()
        ) {
            send(
                viewer,
                WrapperPlayServerTeams(
                    packet.teamName,
                    TeamMode.REMOVE_ENTITIES,
                    null as ScoreBoardTeamInfo?,
                    normalPlayers
                )
            )
        }

        decoratedPlayers.forEach { name ->

            val target =
                Bukkit.getPlayerExact(name)
                    ?: return@forEach

            removeDecoration(
                viewer,
                target
            )
        }
    }

    private fun handleRemoveTeam(
        viewer: Player,
        packet: WrapperPlayServerTeams,
        event: PacketSendEvent,
        viewerTeams: MutableMap<String, TeamState>,
        viewerPlayerTeams: MutableMap<String, String>
    ) {
        val state =
            viewerTeams.remove(
                packet.teamName
            )
                ?: return

        state.players.forEach { name ->

            if (
                viewerPlayerTeams[name] ==
                packet.teamName
            ) {
                viewerPlayerTeams.remove(name)
            }
        }

        val decoratedPlayers =
            state.players.filter {
                isDecorated(
                    viewer,
                    it
                )
            }

        if (
            decoratedPlayers.isEmpty()
        ) {
            return
        }

        event.setCancelled(true)

        /*
         * Remove the original team.
         */
        send(
            viewer,
            WrapperPlayServerTeams(
                packet.teamName,
                TeamMode.REMOVE,
                null as ScoreBoardTeamInfo?,
                emptyList<String>()
            )
        )

        decoratedPlayers.forEach { name ->

            val target =
                Bukkit.getPlayerExact(name)
                    ?: return@forEach

            removeDecoration(
                viewer,
                target
            )
        }
    }

    /**
     * Refresh a player's nameplate after voice state changes.
     */
    private fun refresh(
        viewer: Player,
        target: Player
    ) {
        val playerName =
            target.name

        val viewerPlayerTeams =
            playerTeams[
                viewer.uniqueId
            ]

        val viewerTeams =
            teams[
                viewer.uniqueId
            ]

        val originalTeamName =
            viewerPlayerTeams
                ?.get(playerName)

        val original =
            originalTeamName?.let {
                viewerTeams?.get(it)
            }

        /*
         * Muted:
         * restore the normal nameplate.
         */
        if (
            !shouldDecorate(
                viewer,
                playerName
            )
        ) {
            if (
                isDecorated(
                    viewer,
                    playerName
                )
            ) {
                removeDecoration(
                    viewer,
                    target
                )
            }

            return
        }

        /*
         * Existing rank/team.
         */
        if (
            original != null
        ) {
            if (
                isDecorated(
                    viewer,
                    playerName
                )
            ) {
                updateDecorated(
                    viewer = viewer,
                    target = target,
                    original = original
                )
            } else {
                decorate(
                    viewer = viewer,
                    target = target,
                    original = original
                )
            }

            return
        }

        /*
         * No scoreboard team.
         */
        if (
            !isDecorated(
                viewer,
                playerName
            )
        ) {
            decorateWithoutOriginalTeam(
                viewer = viewer,
                target = target
            )
        } else {
            updateNoTeamDecoration(
                viewer = viewer,
                target = target
            )
        }
    }

    /**
     * Move target into a private client-side team.
     */
    private fun decorate(
        viewer: Player,
        target: Player,
        original: TeamState
    ) {
        val key =
            ViewerTarget(
                viewer = viewer.uniqueId,
                target = target.uniqueId
            )

        val fakeTeam =
            fakeTeamName(
                viewer,
                target
            )

        /*
         * Remove from original team on this client.
         */
        if (
            decorated.add(key)
        ) {
            send(
                viewer,
                WrapperPlayServerTeams(
                    original.teamName,
                    TeamMode.REMOVE_ENTITIES,
                    null as ScoreBoardTeamInfo?,
                    listOf(target.name)
                )
            )
        }

        /*
         * Create our private copy.
         */
        send(
            viewer,
            WrapperPlayServerTeams(
                fakeTeam,
                TeamMode.CREATE,
                decoratedInfo(
                    target = target,
                    original = original.info
                ),
                listOf(target.name)
            )
        )
    }

    private fun updateDecorated(
        viewer: Player,
        target: Player,
        original: TeamState
    ) {
        decorated.add(
            ViewerTarget(
                viewer = viewer.uniqueId,
                target = target.uniqueId
            )
        )

        send(
            viewer,
            WrapperPlayServerTeams(
                fakeTeamName(
                    viewer,
                    target
                ),
                TeamMode.UPDATE,
                decoratedInfo(
                    target = target,
                    original = original.info
                ),
                emptyList<String>()
            )
        )
    }

    private fun decorateWithoutOriginalTeam(
        viewer: Player,
        target: Player
    ) {
        decorated.add(
            ViewerTarget(
                viewer = viewer.uniqueId,
                target = target.uniqueId
            )
        )

        send(
            viewer,
            WrapperPlayServerTeams(
                fakeTeamName(
                    viewer,
                    target
                ),
                TeamMode.CREATE,
                decoratedInfo(
                    target = target,
                    original = defaultTeamInfo()
                ),
                listOf(target.name)
            )
        )
    }

    private fun updateNoTeamDecoration(
        viewer: Player,
        target: Player
    ) {
        send(
            viewer,
            WrapperPlayServerTeams(
                fakeTeamName(
                    viewer,
                    target
                ),
                TeamMode.UPDATE,
                decoratedInfo(
                    target = target,
                    original = defaultTeamInfo()
                ),
                emptyList<String>()
            )
        )
    }

    /**
     * Remove EchoWave client-side team and restore
     * original membership.
     */
    private fun removeDecoration(
        viewer: Player,
        target: Player
    ) {
        val key =
            ViewerTarget(
                viewer = viewer.uniqueId,
                target = target.uniqueId
            )

        if (
            !decorated.remove(key)
        ) {
            return
        }

        val fakeTeam =
            fakeTeamName(
                viewer,
                target
            )

        /*
         * Remove EchoWave team.
         */
        send(
            viewer,
            WrapperPlayServerTeams(
                fakeTeam,
                TeamMode.REMOVE,
                null as ScoreBoardTeamInfo?,
                emptyList<String>()
            )
        )

        /*
         * Restore original team membership.
         */
        val originalTeam =
            playerTeams[
                viewer.uniqueId
            ]?.get(target.name)

        if (
            originalTeam != null
        ) {
            send(
                viewer,
                WrapperPlayServerTeams(
                    originalTeam,
                    TeamMode.ADD_ENTITIES,
                    null as ScoreBoardTeamInfo?,
                    listOf(target.name)
                )
            )
        }
    }

    /**
     * Add voice prefix while retaining all original
     * team formatting.
     */
    private fun decoratedInfo(
        target: Player,
        original: ScoreBoardTeamInfo
    ): ScoreBoardTeamInfo {

        val state =
            voiceStates[
                target.uniqueId
            ]

        val voicePrefix =
            when {
                state?.muted == true ->
                    Component.empty()

                state?.speaking == true ->
                    GREEN_DOT

                else ->
                    YELLOW_DOT
            }

        val originalPrefix =
            original.prefix
                ?: Component.empty()

        val prefix =
            voicePrefix.append(
                originalPrefix
            )

        return ScoreBoardTeamInfo(
            original.displayName,
            prefix,
            original.suffix,
            original.tagVisibility,
            original.collisionRule,
            original.color,
            original.optionData
        )
    }

    /**
     * Default team information for players with
     * no scoreboard team.
     */
    private fun defaultTeamInfo():
        ScoreBoardTeamInfo {

        return ScoreBoardTeamInfo(
            Component.empty(),
            Component.empty(),
            Component.empty(),
            WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
            WrapperPlayServerTeams.CollisionRule.ALWAYS,
            NamedTextColor.WHITE,
            WrapperPlayServerTeams.OptionData.NONE
        )
    }

    private fun shouldDecorate(
        viewer: Player,
        playerName: String
    ): Boolean {

        if (
            viewer.name.equals(
                playerName,
                ignoreCase = true
            )
        ) {
            return false
        }

        val target =
            Bukkit.getPlayerExact(
                playerName
            ) ?: return false

        val state =
            voiceStates[
                target.uniqueId
            ] ?: return false

        return !state.muted
    }

    private fun isDecorated(
        viewer: Player,
        playerName: String
    ): Boolean {

        val target =
            Bukkit.getPlayerExact(
                playerName
            ) ?: return false

        return decorated.contains(
            ViewerTarget(
                viewer = viewer.uniqueId,
                target = target.uniqueId
            )
        )
    }

    /**
     * PacketEvents 2.13.0 provides sendPacketSilently.
     *
     * This prevents our generated team packets from
     * entering our own outgoing listener again.
     */
    private fun send(
        viewer: Player,
        packet: WrapperPlayServerTeams
    ) {
        PacketEvents.getAPI()
            .playerManager
            .sendPacketSilently(
                viewer,
                packet
            )
    }

    /**
     * Client-side team names must be <= 32 characters.
     */
    private fun fakeTeamName(
        viewer: Player,
        target: Player
    ): String {

        return (
            ECHO_TEAM_PREFIX +
                viewer.uniqueId
                    .toString()
                    .replace("-", "")
                    .take(8) +
                "_" +
                target.uniqueId
                    .toString()
                    .replace("-", "")
                    .take(8)
            ).take(32)
    }

    private data class VoiceState(
        val speaking: Boolean,
        val muted: Boolean
    )

    private data class ViewerTarget(
        val viewer: UUID,
        val target: UUID
    )

    private data class TeamState(
        val teamName: String,
        @Volatile
        var info: ScoreBoardTeamInfo,
        val players: MutableSet<String>
    )
}
