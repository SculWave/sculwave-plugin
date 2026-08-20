@file:Suppress("DEPRECATION")

package org.nxblack.echoWave

import kotlinx.coroutines.*
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.nxblack.echoWave.data.*
import org.nxblack.echoWave.data.BackendState.backendId
import org.nxblack.echoWave.data.BackendState.instanceId
import org.nxblack.echoWave.voice.VoiceIndicatorManager
import org.nxblack.echoWave.voice.VoiceNameplateManager
import org.nxblack.echoWave.voice.VoiceStatusDisplay
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

class EchoWave : JavaPlugin(), Listener {
    companion object {
        const val SERVER_URL = "http://129.121.120.245:80"
        const val GROUP_CHARS ="ABCDEFGHJKLMNPQRSTUVWXYZ34578"
    }


    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val playerSettings = ConcurrentHashMap<UUID, PlayerSettings>()
    val groups = ConcurrentHashMap<String, Group>()
    val pendingInvites = ConcurrentHashMap<UUID, MutableList<GroupInvite>>()
    private val voiceBannedPlayers = ConcurrentHashMap.newKeySet<UUID>()
    val voiceStateManager = VoiceStateManager()
    val voiceIndicatorManager = VoiceIndicatorManager(this)
    private lateinit var backendSocket: BackendSocketClient
    val voiceStatusDisplay = VoiceStatusDisplay(this)
    val voiceNameplateManager = VoiceNameplateManager(this)

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pendingInvites.remove(event.player.uniqueId)
        playerSettings.remove(event.player.uniqueId)

        voiceIndicatorManager.clear(event.player)
        voiceNameplateManager.clear(event.player)
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player

        if (BackendState.roomCode.isBlank()) {
            return
        }

        player.sendMessage("§7Voice Room: §f${BackendState.roomCode}")
        player.sendMessage("§7Use this code in the EchoWave app.")

        voiceIndicatorManager.update(player)
    }

    override fun onEnable() {
        logger.info("====================================")
        logger.info("EchoWave initialized successfully!")
        logger.info("====================================")
        saveDefaultConfig()

        loadBackendConfig()
        scope.launch {

            if (!checkServer()) {
                Bukkit.getScheduler().runTask(
                    this@EchoWave,
                    Runnable {
                        server.pluginManager.disablePlugin(this@EchoWave)
                    }
                )
                return@launch
            }

            if (!activatePlugin()) {
                Bukkit.getScheduler().runTask(
                    this@EchoWave,
                    Runnable {
                        server.pluginManager.disablePlugin(this@EchoWave)
                    }
                )
                return@launch
            }

            backendSocket = BackendSocketClient(
                plugin = this@EchoWave,
                serverUrl = SERVER_URL,
                backendId = backendId,
                instanceId = instanceId
            )

            backendSocket.connect()



        }

        getCommand("echo")!!.apply {
            setExecutor(EchoCommand(this@EchoWave))
            tabCompleter = EchoTabCompleter(this@EchoWave)
        }


        server.pluginManager.registerEvents(this,this)

        voiceStateManager.subscribe { state ->
            server.getPlayerExact(state.gamertag)?.let { target ->

                Bukkit.getOnlinePlayers()
                    .filter { it.uniqueId != target.uniqueId }
                    .forEach { viewer ->
                        voiceNameplateManager.update(
                            viewer = viewer,
                            target = target,
                            speaking = state.speaking
                        )
                    }
            }
        }
        if (Platform.isFolia) {
            startFoliaScheduler()
        } else {
            startBukkitScheduler()
        }


    }

    override fun onDisable() {
        voiceNameplateManager.clearAll()
        voiceIndicatorManager.clearAll()
        backendSocket.disconnect()
        scope.cancel()
    }


    private fun loadBackendConfig() {
        instanceId =
            config.getString("instance-id").orEmpty()

        backendId =
            config.getString("backend-id").orEmpty()
    }

    private fun updatePositions() {

        if (BackendState.roomCode.isBlank() ||
            BackendState.authToken.isBlank())
            return

        val payload = Bukkit.getOnlinePlayers().map {

            val settings = getPlayerSettings(it)

            PositionPayload(
                gamertag = it.name,
                x = it.location.blockX,
                y = it.location.blockY,
                z = it.location.blockZ,
                dimension = it.world.name,
                groupId = settings.groupId,
                isMuted = settings.muted
            )

        }

        scope.launch {

            backendSocket.sendHeartbeat(
                payload,
                config.getBoolean("voice-enabled")
            )
        }
    }


    private fun startFoliaScheduler() {
        server.globalRegionScheduler.runAtFixedRate(
            this,
            { _ ->
                updatePositions()
            },
            20L,
            20L
        )
    }
    private fun startBukkitScheduler() {
        server.scheduler.runTaskTimer(
            this,
            Runnable {
                updatePositions()
            },
            20L,
            20L
        )
    }

    fun isVoiceBanned(player: Player): Boolean {
        return player.uniqueId in voiceBannedPlayers
    }

    fun setVoiceBan(player: Player, banned: Boolean) {
        if (banned) {
            voiceBannedPlayers.add(player.uniqueId)
        } else {
            voiceBannedPlayers.remove(player.uniqueId)
        }

        config.set(
            "voice-bans",
            voiceBannedPlayers.map(UUID::toString)
        )

        saveConfig()
    }

    fun generatePartyCode(): String {

        while (true) {
            val code = buildString {
                repeat(4) {append( GROUP_CHARS.random())}
            }
            if (!groups.containsKey(code))
                return code
        }
    }

    fun getPlayerSettings(player: Player): PlayerSettings {
        return playerSettings.computeIfAbsent(player.uniqueId) {
            PlayerSettings()
        }
    }

    fun joinGroup(player: Player,code: String){
        val invites = pendingInvites[player.uniqueId]

        if(invites == null){
            player.sendMessage("§cYou don't have a pending invite.")
            return
        }

        val invite = invites.firstOrNull { it.code == code }

        if(invite == null){
            player.sendMessage("§cInvalid join code.")
            return
        }

        val group = groups[invite.partyId]

        if(group == null){
            player.sendMessage("§cParty no longer exists.")
            pendingInvites.remove(player.uniqueId)
            return
        }

        val settings = getPlayerSettings(player)

        if (!settings.groupId.isNullOrBlank()) {
            player.sendMessage("§cLeave your current group first.")
            return
        }
        group.members.add(player.uniqueId)

        settings.groupId = invite.partyId

        pendingInvites[player.uniqueId]?.remove(invite)

        if (pendingInvites[player.uniqueId].isNullOrEmpty()) {
            pendingInvites.remove(player.uniqueId)
        }

        player.sendMessage("§aJoined the group.")

        group.members.forEach {
            val member = Bukkit.getPlayer(it) ?: return@forEach

            member.sendMessage("§b${player.name} joined the group.")
            playConfiguredSound(member, "sounds.join")
        }


    }

    fun leaveGroup(player: Player) {
        val settings = getPlayerSettings(player)

        val groupId = settings.groupId ?: run {
            player.sendMessage("§cYou are not in a group.")
            return
        }

        val group = groups[groupId] ?: return

        if (group.owner == player.uniqueId) {

            group.members.remove(player.uniqueId)
            settings.groupId = null

            if (group.members.isEmpty()) {
                groups.remove(groupId)
                player.sendMessage("§cGroup disbanded.")
                return
            }

            val newOwner = group.members.first()
            group.owner = newOwner

            player.sendMessage("§eYou left the group.")

            group.members.forEach {
                val member = Bukkit.getPlayer(it)

                if (member != null) {
                    member.sendMessage("§e${player.name} left the group.")
                    member.sendMessage(
                        "§a${Bukkit.getOfflinePlayer(newOwner).name} is now the group owner."
                    )
                }
            }

            return
        }

        group.members.remove(player.uniqueId)
        settings.groupId = null

        player.sendMessage("§eYou left the group.")

        group.members.forEach {
            Bukkit.getPlayer(it)?.sendMessage(
                "§e${player.name} left the group."
            )
        }
    }

    fun showMembers(player: Player) {
        val settings = getPlayerSettings(player)
        val group = groups[settings.groupId] ?: run {
            player.sendMessage("§cYou are not in a group.")
            return
        }
        player.sendMessage("§b===== EchoWave Group =====")
        player.sendMessage(
            "§eOwner: ${
                Bukkit.getOfflinePlayer(group.owner).name
            }"
        )

        player.sendMessage("§aMembers:")
        group.members.forEach {
            player.sendMessage(
                " §7• ${
                    Bukkit.getOfflinePlayer(it).name
                }"
            )
        }

        player.sendMessage("§7Code: §f${group.joinCode}")
    }

    fun createGroup(player: Player){
        val settings = getPlayerSettings(player)

        if (!settings.groupId.isNullOrBlank()) {
            player.sendMessage("§c[Echowave] Leave your current Group first.")
            return
        }

        val groupId = generatePartyCode()
        groups[groupId] = Group(
            owner = player.uniqueId,
            members = mutableSetOf(player.uniqueId),
            joinCode = groupId
        )
        settings.groupId = groupId
        player.sendMessage("§a[Echowave] Group created")


    }

    fun invitePlayer(player: Player,target: String){
        val settings = getPlayerSettings(player)
        if (settings.groupId.isNullOrBlank()) {
            createGroup(player)
        }
        if(settings.groupId == null) return

        val group = groups[settings.groupId] ?: return

        if(group.owner != player.uniqueId){
            player.sendMessage("§c[Echowave] Only the group owner can invite players.")
            return
        }

        val inPlayer = Bukkit.getOnlinePlayers()
            .find { it.name.equals(target, ignoreCase = true) }

        if(inPlayer == null){
            player.sendMessage("§c[Echowave] $target is not online.")
            return
        }

        if(inPlayer.name == player.name){
            player.sendMessage("§c[Echowave] You cannot invite yourself.")
            return
        }

        if(inPlayer.uniqueId in group.members){
            player.sendMessage("§e[Echowave] ${inPlayer.name} is already in your Group.")
            return
        }

        val invites = pendingInvites.getOrPut(inPlayer.uniqueId) { mutableListOf() }

        val alreadyInvited = invites.any { invite ->
            invite.partyId == settings.groupId
        }
        if (alreadyInvited) {
            player.sendMessage("§e[Echowave] ${inPlayer.name} already has an invite.")
            return
        }
        invites.add(GroupInvite(partyId = settings.groupId!!, code = group.joinCode))

        inPlayer.sendMessage("§a[Echowave] ${player.name} invited you to a Group. §b${group.joinCode}")
        player.sendMessage("§a[Echowave] Invite sent to ${inPlayer.name}.")
        playConfiguredSound(inPlayer,"sounds.invite")
        playConfiguredSound(player,"sounds.invite-sent")


    }

    fun kickPlayer(player: Player, target: String) {
        val settings = getPlayerSettings(player)

        val groupId = settings.groupId ?: run {
            player.sendMessage("§c[Echowave] You are not in a Group.")
            return
        }

        val group = groups[groupId] ?: return

        if (group.owner != player.uniqueId) {
            player.sendMessage("§c[Echowave] Only the group owner can kick players.")
            return
        }

        val targetPlayer = Bukkit.getOnlinePlayers()
            .find { it.name.equals(target, ignoreCase = true) }

        if (targetPlayer == null) {
            player.sendMessage("§c[Echowave] $target is not online.")
            return
        }

        if (targetPlayer.uniqueId == player.uniqueId) {
            player.sendMessage("§c[Echowave] Use /echo leave instead.")
            return
        }

        if (targetPlayer.uniqueId !in group.members) {
            player.sendMessage("§c[Echowave] ${targetPlayer.name} is not in your group.")
            return
        }

        group.members.remove(targetPlayer.uniqueId)
        getPlayerSettings(targetPlayer).groupId = null

        targetPlayer.sendMessage("§cYou were kicked from the group.")
        playConfiguredSound(targetPlayer,"sounds.kick")

        group.members.forEach {
            Bukkit.getPlayer(it)?.sendMessage(
                "§e${targetPlayer.name} was kicked from the group."
            )
        }

        player.sendMessage("§a${targetPlayer.name} has been kicked.")
    }

    fun transferOwnership(player: Player, target: String) {
        val settings = getPlayerSettings(player)

        val groupId = settings.groupId ?: run {
            player.sendMessage("§c[Echowave] You are not in a group.")
            return
        }

        val group = groups[groupId] ?: return

        if (group.owner != player.uniqueId) {
            player.sendMessage("§c[Echowave] Only the group owner can transfer ownership.")
            return
        }

        val targetPlayer = Bukkit.getOnlinePlayers()
            .find { it.name.equals(target, ignoreCase = true) }

        if (targetPlayer == null) {
            player.sendMessage("§c[Echowave] $target is not online.")
            return
        }

        if (targetPlayer.uniqueId !in group.members) {
            player.sendMessage("§c[Echowave] ${targetPlayer.name} is not in your group.")
            return
        }

        if (targetPlayer.uniqueId == player.uniqueId) {
            player.sendMessage("§c[Echowave] You are already the owner.")
            return
        }

        group.owner = targetPlayer.uniqueId

        group.members.forEach {
            Bukkit.getPlayer(it)?.let { member ->
                member.sendMessage(
                    "§a${targetPlayer.name} is now the group owner."
                )
                playConfiguredSound(member, "sounds.transfer")
            }
        }
    }

    fun reload(sender: Player) {

        reloadConfig()

        voiceBannedPlayers.clear()

        config.getStringList("voice-bans")
            .mapNotNull {
                runCatching { UUID.fromString(it) }.getOrNull()
            }
            .forEach(voiceBannedPlayers::add)

        loadBackendConfig()

        scope.launch {
            backendId = ""
            instanceId = ""

            if (backendId.isBlank()) {

                activatePlugin()

            }

            backendSocket.disconnect()

            backendSocket = BackendSocketClient(
                plugin = this@EchoWave,
                serverUrl = SERVER_URL,
                backendId = backendId,
                instanceId = instanceId
            )

            backendSocket.connect()

        }

        sender.sendMessage("§aEchoWave configuration reloaded.")

    }

    fun playConfiguredSound(player: Player, path: String) {
        if (!config.getBoolean("sounds.enabled")) return

        val soundName = config.getString(path) ?: return

        val key = runCatching {
            NamespacedKey.minecraft(soundName)
        }.getOrElse {
            logger.warning("Invalid sound key: $soundName")
            return
        }

        val sound = Registry.SOUNDS.get(key)

        if (sound == null) {
            logger.warning("Unknown sound: $soundName")
            return
        }

        player.playSound(player.location, sound, 1f, 1f)
    }

    fun updateMuteNameTag(player: Player) {
        val settings = getPlayerSettings(player)

        if (settings.muted) {
            player.playerListName(Component.text("§7[MUTED] §r${player.name}"))
        } else {
            player.playerListName((Component.text(player.name)))
        }
    }

    private suspend fun activatePlugin() : Boolean {

        try {
            val activationKey =
                config.getString("activation-key").orEmpty()
            if (activationKey.isBlank()) {
                logger.severe("activation-key cannot be empty!")
                server.pluginManager.disablePlugin(this)
                return false
            }
            val requestBody = Json.encodeToString(
                ActivateRequest(
                    activationKey = activationKey,
                    backendId = backendId.ifBlank { null },
                    serverName = server.name,
                    platform = "paper",
                    pluginVersion = pluginMeta.version,
                    minecraftVersion = Bukkit.getMinecraftVersion(),
                )
            )

            val request = HttpRequest.newBuilder()
                .uri(URI.create("$SERVER_URL/api/backend/activate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build()


            logger.info("========== ACTIVATE ==========")
            logger.info("SERVER_URL = $SERVER_URL")
            logger.info("URI = ${request.uri()}")
            logger.info("BODY = $requestBody")
            val response = withContext(Dispatchers.IO) {
                Http.client.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
                )
            }


            if (response.statusCode() != 200) {

                logger.severe("Plugin activation failed. HTTP ${response.statusCode()}")
                logger.severe("Response: ${response.body()}")
                server.pluginManager.disablePlugin(this)

                return false
            }
            val result = Json.decodeFromString<ActivateResponse>(response.body())
            config.set("instance-id", result.instanceId)
            config.set("backend-id", result.backendId)
            saveConfig()

            instanceId = result.instanceId
            backendId = result.backendId
            logger.info("====================================")
            logger.info("EchoWave activated successfully!")
            logger.info("====================================")

        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Activation failed", e)
            return false
        }


        return true
    }

    private fun checkServer(): Boolean {
        return try {
            val connection = URL("$SERVER_URL/health").openConnection() as HttpURLConnection

            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000

            val code = connection.responseCode

            val stream =
                if (code in 200..299)
                    connection.inputStream
                else
                    connection.errorStream

            val body = stream?.bufferedReader()?.use { it.readText() }

            logger.info("Status = $code")
            logger.info(body ?: "No body")

            code == HttpURLConnection.HTTP_OK

        } catch (e: Exception) {
            logger.log(Level.SEVERE, "Health check failed", e)
            false
        }
    }
}
