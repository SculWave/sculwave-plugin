package org.nxblack.sculwave.voice

import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.`object`.ObjectContents
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.scheduler.BukkitTask
import org.nxblack.sculwave.SculWave
import org.nxblack.sculwave.data.Platform
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/** [glyph] is the mic icon in the SculWave icon packs (private-use characters U+E500..U+E503). */
enum class VoiceStatus(val color: NamedTextColor, val label: String, val glyph: Char) {
    SPEAKING(NamedTextColor.GREEN, "Speaking", '\uE500'),
    CONNECTED(NamedTextColor.AQUA, "Mic on", '\uE501'),
    MUTED(NamedTextColor.RED, "Muted", '\uE502'),
    NOT_CONNECTED(NamedTextColor.DARK_GRAY, "Not connected", '\uE503'),
}

/**
 * Action-bar HUD for players who use SculWave voice (Java or Bedrock):
 *
 *   ● Speaking  |  [head]● [head]● [head]●        (Java: group members as player heads)
 *   ● Speaking  |  ● Alex ● Steve                  (Bedrock: coloured dot + name)
 *
 * Players on the Simple Voice Chat mod are skipped: the mod draws its own HUD for them. They still
 * appear in other players' group lists, with their speaking / muted state.
 */
class VoiceHud(private val plugin: SculWave) : Listener {

    companion object {
        /** Action bars fade after ~3 s, so an unchanged line is re-sent this often. */
        private const val RESEND_MS = 2_000L

        /** Id of the Java icon pack (must stay the same so clients can tell it is already loaded). */
        val PACK_ID: UUID = UUID.fromString("6e1ab496-06e0-4bae-a138-0e0e416ba7d2")

        /** Font that holds the icons in the Java pack. Bedrock ignores it and uses glyph page E5. */
        private val ICON_FONT = Key.key("sculwave", "voice")
    }

    @Volatile private var enabled = true
    @Volatile private var periodTicks = 4L
    @Volatile private var showGroup = true
    @Volatile private var useHeads = true
    @Volatile private var javaNames = false
    @Volatile private var maxMembers = 6
    @Volatile private var bedrockIcons = false
    @Volatile private var javaPackUrl = ""
    @Volatile private var javaPackSha1: ByteArray? = null

    private class Sent(var line: Component? = null, var at: Long = 0L)

    private val sent = ConcurrentHashMap<UUID, Sent>()
    private val hidden = ConcurrentHashMap.newKeySet<UUID>()      // players who ran /vc hud off
    private val failed = ConcurrentHashMap.newKeySet<UUID>()      // log a render error once per player
    private val packLoaded = ConcurrentHashMap.newKeySet<UUID>()  // Java players that have the icon pack
    private var bukkitTask: BukkitTask? = null

    // ------------------------------------------------------------------ lifecycle

    fun reloadConfig() {
        val c = plugin.config
        enabled = c.getBoolean("hud.enabled", true)
        periodTicks = c.getLong("hud.update-ticks", 4L).coerceAtLeast(1L)
        showGroup = c.getBoolean("hud.show-group", true)
        useHeads = c.getBoolean("hud.heads", true)
        javaNames = c.getBoolean("hud.java-names", false)
        maxMembers = c.getInt("hud.max-members", 6).coerceIn(1, 20)
        bedrockIcons = c.getBoolean("hud.bedrock-icons", false)
        javaPackUrl = c.getString("hud.java-pack.url", "")!!.trim()
        javaPackSha1 = parseSha1(c.getString("hud.java-pack.sha1", "")!!)

        plugin.logger.info(
            "[HUD] enabled=$enabled every=${periodTicks}t group=$showGroup heads=$useHeads " +
                "names=$javaNames max=$maxMembers bedrockIcons=$bedrockIcons " +
                "javaPack=${if (javaPackUrl.isEmpty()) "off" else javaPackUrl}"
        )
        if (javaPackUrl.isNotEmpty() && javaPackSha1 == null) {
            plugin.logger.warning("[HUD] hud.java-pack.sha1 is missing or not a 40-character hex string; clients will not cache the pack.")
        }

        if (!Platform.isFolia) {
            bukkitTask?.cancel()
            bukkitTask = null
            start()
        }
    }

    /** Spigot/Paper: one timer for everyone. (On Folia each player gets their own, see [onJoin].) */
    fun start() {
        if (Platform.isFolia || bukkitTask != null) return

        bukkitTask = plugin.server.scheduler.runTaskTimer(
            plugin,
            Runnable { Bukkit.getOnlinePlayers().forEach(::tick) },
            20L,
            periodTicks
        )
        plugin.debugLog("[HUD] timer started")
    }

    /** Folia: schedule on the player's own region. */
    fun onJoin(player: Player) {
        sendPack(player)

        if (!Platform.isFolia) return

        player.scheduler.runAtFixedRate(
            plugin,
            { task -> if (player.isOnline) tick(player) else task.cancel() },
            null,
            20L,
            periodTicks
        )
    }

    fun onQuit(player: Player) {
        sent.remove(player.uniqueId)
        hidden.remove(player.uniqueId)
        failed.remove(player.uniqueId)
        packLoaded.remove(player.uniqueId)
    }

    fun shutdown() {
        bukkitTask?.cancel()
        bukkitTask = null
        sent.clear()
    }

    // --------------------------------------------------------------- Java icon pack

    private fun parseSha1(hex: String): ByteArray? {
        val clean = hex.trim()
        if (clean.length != 40 || !clean.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(20) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Offers the icon pack to Java players. Not forced: players who decline just see dots. */
    private fun sendPack(player: Player) {
        if (javaPackUrl.isEmpty() || isBedrock(player)) return

        try {
            player.addResourcePack(PACK_ID, javaPackUrl, javaPackSha1, "SculWave voice icons", false)
            plugin.debugLog("[HUD] offered the icon pack to ${player.name}")
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "[HUD] Could not offer the icon pack to ${player.name}", e)
        }
    }

    @EventHandler
    fun onPackStatus(event: PlayerResourcePackStatusEvent) {
        if (event.id != PACK_ID) return

        val player = event.player
        val loaded = event.status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED

        if (loaded) packLoaded.add(player.uniqueId) else if (event.status.isTerminal()) packLoaded.remove(player.uniqueId)

        plugin.debugLog("[HUD] icon pack status for ${player.name}: ${event.status}")
    }

    private fun PlayerResourcePackStatusEvent.Status.isTerminal() = this != PlayerResourcePackStatusEvent.Status.ACCEPTED &&
        this != PlayerResourcePackStatusEvent.Status.DOWNLOADED

    /** Can this viewer see the mic icons? Otherwise the HUD falls back to plain dots. */
    private fun hasIcons(viewer: Player): Boolean =
        if (isBedrock(viewer)) bedrockIcons else packLoaded.contains(viewer.uniqueId)

    /** Mic icon, or a coloured dot for viewers without the pack. */
    private fun marker(status: VoiceStatus, viewer: Player): Component =
        if (hasIcons(viewer)) {
            // White so the icon keeps its own colours (text colour tints glyphs).
            Component.text(status.glyph.toString(), NamedTextColor.WHITE).font(ICON_FONT)
        } else {
            Component.text("●", status.color)
        }

    // ------------------------------------------------------------------- toggle

    /** `/vc hud [on|off]`. Returns true when the HUD is now on for this player. */
    fun toggle(player: Player, want: Boolean?): Boolean {
        val on = want ?: hidden.contains(player.uniqueId)

        if (on) hidden.remove(player.uniqueId) else hidden.add(player.uniqueId)
        if (!on) clear(player)

        plugin.debugLog("[HUD] ${player.name} turned HUD ${if (on) "on" else "off"}")
        return on
    }

    private fun clear(player: Player) {
        val state = sent.remove(player.uniqueId) ?: return
        if (state.line != null) player.sendActionBar(Component.empty())
    }

    // ------------------------------------------------------------------- status

    /** One status for any player, whichever voice system they use. */
    fun statusOf(player: Player): VoiceStatus {
        val settings = plugin.playerSettings[player.uniqueId]
        val mutedByCommand = settings?.muted == true

        // Simple Voice Chat mod player.
        if (plugin.svcBridge.connected.containsKey(player.uniqueId)) {
            return when {
                mutedByCommand || plugin.svcBridge.micMuted.contains(player.uniqueId) -> VoiceStatus.MUTED
                plugin.svcBridge.isSpeaking(player.uniqueId) -> VoiceStatus.SPEAKING
                else -> VoiceStatus.CONNECTED
            }
        }

        // SculWave app player.
        val state = plugin.voiceStateManager.get(player.name)
        return when {
            state == null || !state.connected -> VoiceStatus.NOT_CONNECTED
            mutedByCommand || state.muted -> VoiceStatus.MUTED
            state.speaking -> VoiceStatus.SPEAKING
            else -> VoiceStatus.CONNECTED
        }
    }

    // ------------------------------------------------------------------- render

    /** Bedrock (Floodgate) players have a UUID whose most significant bits are zero. */
    private fun isBedrock(player: Player) = player.uniqueId.mostSignificantBits == 0L

    private fun wantsHud(player: Player): Boolean =
        enabled &&
            plugin.config.getBoolean("voice-enabled", true) &&
            !hidden.contains(player.uniqueId) &&
            !plugin.isVoiceBanned(player) &&
            !plugin.svcBridge.connected.containsKey(player.uniqueId)

    private fun groupMembers(viewer: Player): List<Player> {
        if (!showGroup) return emptyList()

        val groupId = plugin.playerSettings[viewer.uniqueId]?.groupId?.takeIf { it.isNotBlank() }
            ?: return emptyList()

        val group = plugin.groups[groupId] ?: return emptyList()

        return group.members.mapNotNull { uuid ->
            if (uuid == viewer.uniqueId) null else Bukkit.getPlayer(uuid)
        }
    }

    private fun render(viewer: Player): Component? {
        val own = statusOf(viewer)
        val members = groupMembers(viewer)

        // Not in voice and no group: show nothing, so other plugins' action bars are untouched.
        if (own == VoiceStatus.NOT_CONNECTED && members.isEmpty()) return null

        var line: Component = marker(own, viewer).append(Component.text(" ${own.label}", own.color))

        if (members.isNotEmpty()) {
            line = line.append(Component.text("  |  ", NamedTextColor.DARK_GRAY))

            val heads = useHeads && !isBedrock(viewer)

            members.take(maxMembers).forEachIndexed { index, member ->
                val status = statusOf(member)
                if (index > 0) line = line.append(Component.text("  "))

                line = if (heads) {
                    var part = line
                        .append(Component.`object`(ObjectContents.playerHead(member.uniqueId)))
                        .append(marker(status, viewer))
                    if (javaNames) part = part.append(Component.text(member.name, status.color))
                    part
                } else {
                    line.append(marker(status, viewer)).append(Component.text(" ${member.name}", status.color))
                }
            }

            if (members.size > maxMembers) {
                line = line.append(Component.text("  +${members.size - maxMembers}", NamedTextColor.GRAY))
            }
        }

        return line
    }

    private fun tick(player: Player) {
        if (!player.isOnline) return

        try {
            val line = if (wantsHud(player)) render(player) else null
            val state = sent.computeIfAbsent(player.uniqueId) { Sent() }

            if (line == null) {
                if (state.line != null) {
                    player.sendActionBar(Component.empty())
                    state.line = null
                }
                return
            }

            val now = System.currentTimeMillis()
            if (line != state.line || now - state.at >= RESEND_MS) {
                player.sendActionBar(line)
                state.line = line
                state.at = now
            }
        } catch (e: Exception) {
            if (failed.add(player.uniqueId)) {
                plugin.logger.log(Level.WARNING, "[HUD] Could not render the HUD for ${player.name}", e)
            }
        }
    }
}
