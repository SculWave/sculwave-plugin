@file:Suppress("DEPRECATION")

package org.nxblack.sculwave.voice

import com.github.retrooper.packetevents.PacketEvents
import com.github.retrooper.packetevents.event.PacketListenerAbstract
import com.github.retrooper.packetevents.event.PacketListenerPriority
import com.github.retrooper.packetevents.event.PacketSendEvent
import com.github.retrooper.packetevents.protocol.packettype.PacketType
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams.*
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.nxblack.sculwave.data.Platform
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

class VoiceNameplateManager(private val plugin: JavaPlugin) {
    companion object {
        private const val TEAM_PREFIX = "ewv"
        private const val LOG_PREFIX = "[VoiceNameplate]"
        private val GREEN_DOT = Component.text(" ●", NamedTextColor.GREEN)
        private val YELLOW_DOT = Component.text(" ●", NamedTextColor.YELLOW)
    }

    private val voiceStates = ConcurrentHashMap<UUID, VoiceState>()
    private val viewerStates = ConcurrentHashMap<UUID, ViewerState>()
    private val nameToUuid = ConcurrentHashMap<String, UUID>()
    private val uuidToName = ConcurrentHashMap<UUID, String>()
    private val updateExecutor = Executors.newSingleThreadScheduledExecutor()
    private val pendingUpdates = ConcurrentHashMap<UUID, ScheduledFuture<*>>()

    private fun log(message: String) {
//        plugin.logger.info("$LOG_PREFIX $message")
    }

    private fun debug(message: String) {
//        plugin.logger.info("$LOG_PREFIX DEBUG: $message")
    }

    private val listener = object : PacketListenerAbstract(PacketListenerPriority.HIGH) {
        override fun onPacketSend(event: PacketSendEvent) {
            if (event.packetType != PacketType.Play.Server.TEAMS) return

            val viewer = event.getPlayer<Player>()
            val packet = WrapperPlayServerTeams(event)

            if (packet.teamName.startsWith(TEAM_PREFIX)) return

            debug(
                "TEAMS packet -> viewer=${viewer.name}, " +
                        "team=${packet.teamName}, " +
                        "mode=${packet.teamMode}, " +
                        "players=${packet.players}"
            )

            handleTeamPacket(viewer, packet, event)
        }
    }

    init {
        log("Initializing VoiceNameplateManager")
        PacketEvents.getAPI().eventManager.registerListener(listener)
        log("PacketEvents TEAMS listener registered")
    }

    fun update(player: Player, speaking: Boolean, muted: Boolean) {
        val uuid = player.uniqueId
        val name = player.name
        val newState = VoiceState(speaking, muted)
        val oldState = voiceStates.put(uuid, newState)

        nameToUuid[name.lowercase()] = uuid
        uuidToName[uuid] = name

        log(
            "VOICE UPDATE player=$name uuid=$uuid " +
                    "speaking=$speaking muted=$muted old=$oldState"
        )

        if (oldState == newState) {
    debug("No voice-state change for $name, refreshing viewer")

    runForViewer(player) {
        refresh(player)
    }

    return
}

        pendingUpdates.remove(uuid)?.cancel(false)

pendingUpdates[uuid] = updateExecutor.schedule({
    debug("Applying voice update to viewers for target=$name")

    runGlobal {
        val viewers = Bukkit.getOnlinePlayers().toList()

        viewers.forEach { viewer ->
            if (viewer.uniqueId == uuid) {
                // Rebuild this player's view after reconnect.
                // This makes already-speaking players visible again.
                runForViewer(viewer) {
                    refresh(viewer)
                }
            } else {
                // Update this player's indicator for everyone else.
                runForViewer(viewer) {
                    refresh(viewer, uuid)
                }
            }
        }
    }

    pendingUpdates.remove(uuid)
}, 50L, TimeUnit.MILLISECONDS)
    }

    fun clear(player: Player) {
        val targetId = player.uniqueId
        val targetName = player.name

        log("CLEAR target=$targetName uuid=$targetId")

        pendingUpdates.remove(targetId)?.cancel(false)
        voiceStates.remove(targetId)
        nameToUuid.remove(targetName.lowercase())
        uuidToName.remove(targetId)

        runGlobal {
            Bukkit.getOnlinePlayers().toList().forEach { viewer ->
                if (viewer.uniqueId == targetId) return@forEach

                runForViewer(viewer) {
                    removeDecoration(viewer, targetId)
                }
            }
        }
    }

    fun refresh(player: Player) {
        runForViewer(player) {
            val viewerId = player.uniqueId

            viewerStates[viewerId]
                ?.decoratedTargets
                ?.toList()
                ?.forEach { targetId ->
                    removeFakeTeamOnly(player, targetId)
                }


            viewerStates[viewerId]?.decoratedTargets?.clear()
            viewerStates[viewerId]?.decorations?.clear()

            voiceStates.keys
                .filter { it != viewerId }
                .forEach { targetId ->
                    refresh(player, targetId)
                }
        }
    }




    fun clearAll() {
        log("CLEAR ALL")

        pendingUpdates.values.forEach { it.cancel(false) }
        pendingUpdates.clear()
        updateExecutor.shutdownNow()

        runGlobal {
            Bukkit.getOnlinePlayers().toList().forEach { viewer ->
                runForViewer(viewer) {
                    viewerStates[viewer.uniqueId]
                        ?.decoratedTargets
                        ?.toList()
                        ?.forEach { targetId ->
                            removeFakeTeamOnly(viewer, targetId)
                        }
                }
            }

            voiceStates.clear()
            viewerStates.clear()
            nameToUuid.clear()
            uuidToName.clear()
        }

        PacketEvents.getAPI().eventManager.unregisterListener(listener)

        log("VoiceNameplateManager disabled")
    }

    private fun handleTeamPacket(
        viewer: Player,
        packet: WrapperPlayServerTeams,
        event: PacketSendEvent
    ) {
        val state = viewerStates.computeIfAbsent(viewer.uniqueId) {
            ViewerState()
        }

        debug(
            "HANDLE TEAM viewer=${viewer.name} " +
                    "team=${packet.teamName} mode=${packet.teamMode}"
        )

        when (packet.teamMode) {
            TeamMode.CREATE -> handleCreate(viewer, state, packet, event)
            TeamMode.UPDATE -> handleUpdate(viewer, state, packet, event)
            TeamMode.ADD_ENTITIES -> handleAddEntities(viewer, state, packet, event)
            TeamMode.REMOVE_ENTITIES -> handleRemoveEntities(viewer, state, packet, event)
            TeamMode.REMOVE -> handleRemoveTeam(viewer, state, packet, event)
        }
    }

    private fun handleCreate(
        viewer: Player,
        state: ViewerState,
        packet: WrapperPlayServerTeams,
        event: PacketSendEvent
    ) {
        val info = packet.teamInfo.orElse(null) ?: return
        val players = packet.players.toList()

        log(
            "TEAM CREATE viewer=${viewer.name} " +
                    "team=${packet.teamName} players=$players"
        )

        state.teams[packet.teamName]?.let { oldTeam ->
            oldTeam.players.forEach { name ->
                if (state.playerTeams[name] == packet.teamName) {
                    state.playerTeams.remove(name)
                }
            }
        }

        val team = TeamState(packet.teamName, info)
        team.players.addAll(players)

        state.teams[packet.teamName] = team
        state.clientTeams.add(packet.teamName)

        players.forEach { name ->
            state.playerTeams[name] = packet.teamName
        }

        val decorated = players.filter {
            shouldDecorate(it, viewer)
        }


        if (decorated.isEmpty()) {
            return
        }

        event.isCancelled = true

        val normal = players.filterNot { it in decorated }
        send(
            viewer,
            WrapperPlayServerTeams(
                packet.teamName,
                TeamMode.CREATE,
                info,
                normal
            )
        )

        decorated.forEach { name ->
            val targetId = nameToUuid[name.lowercase()] ?: run {
                return@forEach
            }

            state.decorations[targetId] = DecorationState(
                originalTeamName = packet.teamName
            )

            ensureDecoration(viewer, targetId)
        }
    }

    private fun handleUpdate(
        viewer: Player,
        state: ViewerState,
        packet: WrapperPlayServerTeams,
        event: PacketSendEvent
    ) {
        val info = packet.teamInfo.orElse(null) ?: return
        val team = state.teams[packet.teamName] ?: run {
            return
        }

        team.info = info

        val decorated = team.players.filter {
            val targetId = nameToUuid[it.lowercase()]
            isDecorated(viewer, targetId)
        }

        if (decorated.isEmpty()) {
            return
        }

        event.isCancelled = true

        send(
            viewer,
            WrapperPlayServerTeams(
                packet.teamName,
                TeamMode.UPDATE,
                info,
                emptyList()
            )
        )

        decorated.forEach { name ->
            val targetId = nameToUuid[name.lowercase()] ?: return@forEach

            state.decorations[targetId]?.originalTeamName = packet.teamName
            updateDecoration(viewer, targetId)
        }
    }

    private fun handleAddEntities(
        viewer: Player,
        state: ViewerState,
        packet: WrapperPlayServerTeams,
        event: PacketSendEvent
    ) {
        val team = state.teams[packet.teamName] ?: run {
            return
        }

        val incoming = packet.players.toList()


        incoming.forEach { name ->
            val previousTeamName = state.playerTeams[name]

            if (previousTeamName != null && previousTeamName != packet.teamName) {

                state.teams[previousTeamName]?.players?.remove(name)
            }

            team.players.add(name)
            state.playerTeams[name] = packet.teamName
        }

        val decorated = incoming.filter {
            shouldDecorate(it, viewer)
        }

        if (decorated.isEmpty()) return

        event.isCancelled = true

        val normal = incoming.filterNot { it in decorated }


        if (normal.isNotEmpty()) {
            send(
                viewer,
                WrapperPlayServerTeams(
                    packet.teamName,
                    TeamMode.ADD_ENTITIES,
                    Optional.empty(),
                    normal
                )
            )
        }

        decorated.forEach { name ->
            val targetId = nameToUuid[name.lowercase()] ?: return@forEach

            state.decorations[targetId]?.originalTeamName = packet.teamName

            if (isDecorated(viewer, targetId)) {
                updateDecoration(viewer, targetId)
            } else {
                state.decorations[targetId] = DecorationState(
                    originalTeamName = packet.teamName
                )

                ensureDecoration(viewer, targetId)
            }
        }
    }

    private fun handleRemoveEntities(
        viewer: Player,
        state: ViewerState,
        packet: WrapperPlayServerTeams,
        event: PacketSendEvent
    ) {
        val team = state.teams[packet.teamName] ?: run {
            return
        }

        val removed = packet.players.toList()


        val decorated = removed.filter { name ->
            val targetId = nameToUuid[name.lowercase()]
            isDecorated(viewer, targetId)
        }

        removed.forEach { name ->
            team.players.remove(name)

            if (state.playerTeams[name] == packet.teamName) {
                state.playerTeams.remove(name)
            }
        }

        if (decorated.isEmpty()) return

        event.isCancelled = true

        val normal = removed.filterNot { it in decorated }

        if (normal.isNotEmpty()) {
            send(
                viewer,
                WrapperPlayServerTeams(
                    packet.teamName,
                    TeamMode.REMOVE_ENTITIES,
                    Optional.empty(),
                    normal
                )
            )
        }

        decorated.forEach { name ->
            val targetId = nameToUuid[name.lowercase()] ?: return@forEach

            val decoration = state.decorations[targetId] ?: return@forEach

            if (decoration.originalTeamName == packet.teamName) {
                decoration.originalTeamName = null
            }

            updateDecoration(viewer, targetId)
        }
    }

    private fun handleRemoveTeam(
        viewer: Player,
        state: ViewerState,
        packet: WrapperPlayServerTeams,
        event: PacketSendEvent
    ) {
        val team = state.teams.remove(packet.teamName) ?: run {
            return
        }

        team.players.forEach { name ->
            if (state.playerTeams[name] == packet.teamName) {
                state.playerTeams.remove(name)
            }
        }

        state.clientTeams.remove(packet.teamName)

        val decorated = team.players.filter { name ->
            val targetId = nameToUuid[name.lowercase()]
            isDecorated(viewer, targetId)
        }

        if (decorated.isEmpty()) return

        event.isCancelled = true

        send(
            viewer,
            WrapperPlayServerTeams(
                packet.teamName,
                TeamMode.REMOVE,
                Optional.empty(),
                emptyList()
            )
        )

        decorated.forEach { name ->
            val targetId = nameToUuid[name.lowercase()] ?: return@forEach

            state.decorations[targetId]?.originalTeamName = null

            debug(
                "Original team removed; target=$name keeps voice " +
                        "decoration for viewer=${viewer.name}"
            )

            updateDecoration(viewer, targetId)
        }
    }

    private fun refresh(
        viewer: Player,
        targetId: UUID
    ) {
        if (viewer.uniqueId == targetId) return

        val targetName = uuidToName[targetId] ?: run {
            debug(
                "REFRESH failed: no name for target UUID=$targetId"
            )
            return
        }

        val voice = voiceStates[targetId]

        debug(
            "REFRESH viewer=${viewer.name} target=$targetName " +
                    "voice=$voice"
        )

        if (voice == null || voice.muted) {
            debug(
                "REFRESH removing decoration viewer=${viewer.name} " +
                        "target=$targetName"
            )

            removeDecoration(viewer, targetId)
            return
        }

        val state = viewerStates.computeIfAbsent(viewer.uniqueId) {
            ViewerState()
        }

        if (state.decorations.containsKey(targetId)) {
            debug(
                "REFRESH existing decoration viewer=${viewer.name} " +
                        "target=$targetName"
            )

            updateDecoration(viewer, targetId)
            return
        }

        val originalTeamName = state.playerTeams[targetName]

        log(
            "REFRESH creating decoration viewer=${viewer.name} " +
                    "target=$targetName originalTeam=$originalTeamName"
        )

        state.decorations[targetId] = DecorationState(
            originalTeamName = originalTeamName
        )

        ensureDecoration(viewer, targetId)
    }

    private fun ensureDecoration(
        viewer: Player,
        targetId: UUID
    ) {
        val state = viewerStates.computeIfAbsent(viewer.uniqueId) {
            ViewerState()
        }

        val targetName = uuidToName[targetId] ?: run {
            debug(
                "ensureDecoration failed: no name for UUID=$targetId"
            )
            return
        }

        val decoration = state.decorations[targetId] ?: run {
            debug(
                "ensureDecoration failed: no DecorationState " +
                        "viewer=${viewer.name} target=$targetName"
            )
            return
        }

        val fakeTeam = fakeTeamName(targetId)

        if (state.decoratedTargets.contains(targetId)) {
            debug(
                "Decoration already exists viewer=${viewer.name} " +
                        "target=$targetName fakeTeam=$fakeTeam"
            )

            updateDecoration(viewer, targetId)
            return
        }

        val originalTeam = decoration.originalTeamName
            ?.let { state.teams[it] }

        log(
            "CREATE DECORATION viewer=${viewer.name} " +
                    "target=$targetName fakeTeam=$fakeTeam " +
                    "originalTeam=${originalTeam?.name ?: "none"}"
        )

        if (originalTeam != null) {
            debug(
                "Removing target=$targetName from original team=" +
                        "${originalTeam.name} for viewer=${viewer.name}"
            )

            send(
                viewer,
                WrapperPlayServerTeams(
                    originalTeam.name,
                    TeamMode.REMOVE_ENTITIES,
                    Optional.empty(),
                    listOf(targetName)
                )
            )
        }

        send(
            viewer,
            WrapperPlayServerTeams(
                fakeTeam,
                TeamMode.CREATE,
                decoratedInfo(targetId, originalTeam),
                listOf(targetName)
            )
        )

        state.decoratedTargets.add(targetId)

        debug(
            "Decoration ACTIVE viewer=${viewer.name} " +
                    "target=$targetName fakeTeam=$fakeTeam"
        )
    }

    private fun updateDecoration(
        viewer: Player,
        targetId: UUID
    ) {
        val state = viewerStates[viewer.uniqueId] ?: return
        val decoration = state.decorations[targetId] ?: return
        val targetName = uuidToName[targetId] ?: return

        val originalTeam = decoration.originalTeamName
            ?.let { state.teams[it] }

        if (!state.decoratedTargets.contains(targetId)) {
            debug(
                "updateDecoration found no active fake team; " +
                        "creating one viewer=${viewer.name} target=$targetName"
            )

            ensureDecoration(viewer, targetId)
            return
        }

        val fakeTeam = fakeTeamName(targetId)

        debug(
            "UPDATE DECORATION viewer=${viewer.name} " +
                    "target=$targetName fakeTeam=$fakeTeam " +
                    "originalTeam=${originalTeam?.name ?: "none"}"
        )

        send(
            viewer,
            WrapperPlayServerTeams(
                fakeTeam,
                TeamMode.UPDATE,
                decoratedInfo(targetId, originalTeam),
                emptyList()
            )
        )
    }

    private fun removeDecoration(
        viewer: Player,
        targetId: UUID
    ) {
        val state = viewerStates[viewer.uniqueId] ?: return
        val targetName = uuidToName[targetId] ?: return

        if (!state.decoratedTargets.contains(targetId)) {
            debug(
                "removeDecoration: target=$targetName " +
                        "not decorated for viewer=${viewer.name}"
            )

            state.decorations.remove(targetId)
            return
        }

        val decoration = state.decorations[targetId]

        removeFakeTeamOnly(viewer, targetId)

        state.decorations.remove(targetId)

        val originalTeamName = decoration?.originalTeamName ?: run {
            debug(
                "No original team to restore for target=$targetName " +
                        "viewer=${viewer.name}"
            )
            return
        }

        val originalTeam = state.teams[originalTeamName] ?: run {
            debug(
                "Original team $originalTeamName no longer exists; " +
                        "nothing to restore for target=$targetName"
            )
            return
        }

        if (!state.clientTeams.contains(originalTeamName)) {
            debug(
                "Original team $originalTeamName is not known client-side; " +
                        "cannot ADD target=$targetName"
            )
            return
        }

        debug(
            "Restoring target=$targetName to original team=" +
                    "$originalTeamName for viewer=${viewer.name}"
        )

        send(
            viewer,
            WrapperPlayServerTeams(
                originalTeam.name,
                TeamMode.ADD_ENTITIES,
                Optional.empty(),
                listOf(targetName)
            )
        )
    }

    private fun removeFakeTeamOnly(
        viewer: Player,
        targetId: UUID
    ) {
        val state = viewerStates[viewer.uniqueId] ?: return
        val targetName = uuidToName[targetId] ?: targetId.toString()

        if (!state.decoratedTargets.remove(targetId)) {
            debug(
                "removeFakeTeamOnly: no fake team for " +
                        "viewer=${viewer.name} target=$targetName"
            )
            return
        }

        val fakeTeam = fakeTeamName(targetId)

        debug(
            "REMOVE FAKE TEAM viewer=${viewer.name} " +
                    "target=$targetName fakeTeam=$fakeTeam"
        )

        send(
            viewer,
            WrapperPlayServerTeams(
                fakeTeam,
                TeamMode.REMOVE,
                Optional.empty(),
                emptyList()
            )
        )
    }

    private fun decoratedInfo(
        targetId: UUID,
        original: TeamState?
    ): ScoreBoardTeamInfo {
        val voice = voiceStates[targetId]

        val dot = when {
            voice?.speaking == true -> GREEN_DOT
            voice?.muted == false -> YELLOW_DOT
            else -> Component.empty()
        }

        val info = original?.info

        return ScoreBoardTeamInfo(
            info?.displayName ?: Component.empty(),
            info?.prefix ?: Component.empty(),
            (info?.suffix ?: Component.empty()).append(dot),
            info?.tagVisibility ?: NameTagVisibility.ALWAYS,
            info?.collisionRule ?: CollisionRule.ALWAYS,
            info?.color ?: NamedTextColor.WHITE,
            info?.optionData ?: OptionData.NONE
        )
    }

    private fun shouldDecorate(
        playerName: String,
        viewer: Player
    ): Boolean {
        val targetId = nameToUuid[playerName.lowercase()] ?: return false

        return targetId != viewer.uniqueId && voiceStates[targetId]?.muted == false
    }

    private fun isDecorated(
        viewer: Player,
        targetId: UUID?
    ): Boolean {
        return targetId != null &&
                viewerStates[viewer.uniqueId]
                    ?.decoratedTargets
                    ?.contains(targetId) == true
    }

    private fun fakeTeamName(targetId: UUID): String {
        val targetPart = targetId
            .toString()
            .replace("-", "")
            .takeLast(13)

        return "$TEAM_PREFIX$targetPart"
    }

    private fun send(
        viewer: Player,
        packet: WrapperPlayServerTeams
    ) {
        debug(
            "SEND fake/rewritten TEAMS viewer=${viewer.name} " +
                    "team=${packet.teamName} mode=${packet.teamMode} " +
                    "players=${packet.players}"
        )

        PacketEvents.getAPI()
            .playerManager
            .sendPacketSilently(viewer, packet)
    }

    private fun runGlobal(task: () -> Unit) {
        if (Platform.isFolia) {
            plugin.server.globalRegionScheduler.run(plugin) {
                task()
            }
        } else {
            Bukkit.getScheduler().runTask(
                plugin,
                Runnable { task() }
            )
        }
    }

    private fun runForViewer(
        viewer: Player,
        task: () -> Unit
    ) {
        if (Platform.isFolia) {
            viewer.scheduler.run(
                plugin,
                { task() },
                null
            )
        } else {
            task()
        }
    }

    private class ViewerState {
        val teams = ConcurrentHashMap<String, TeamState>()
        val playerTeams = ConcurrentHashMap<String, String>()
        val clientTeams = ConcurrentHashMap.newKeySet<String>()!!
        val decoratedTargets = ConcurrentHashMap.newKeySet<UUID>()!!
        val decorations = ConcurrentHashMap<UUID, DecorationState>()
    }

    private class TeamState(
        val name: String,
        @Volatile var info: ScoreBoardTeamInfo
    ) {
        val players = ConcurrentHashMap.newKeySet<String>()!!
    }

    private class DecorationState(
        @Volatile var originalTeamName: String?
    )

    private data class VoiceState(
        val speaking: Boolean,
        val muted: Boolean
    )
}
