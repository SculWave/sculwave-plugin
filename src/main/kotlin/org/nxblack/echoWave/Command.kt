package org.nxblack.echoWave

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class Command(private val plugin: SculWave) : CommandExecutor {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {

        if (sender !is Player)
            return true

        val subcommand = args.firstOrNull()?.lowercase()

        if (plugin.isVoiceBanned(sender) &&
            subcommand !in setOf("voiceban", "voiceunban", "voicecheck")) {
            sender.sendMessage("§c[SculWave] You are banned from voice chat.")
            return true
        }

        if (args.isEmpty()) {
            sender.sendMessage("§SculWave")
            sender.sendMessage("§7/voice m")
            sender.sendMessage("§7/voice invite <player>")
            sender.sendMessage("§7/voice join <code>")
            sender.sendMessage("§7/voice leave")
            sender.sendMessage("§7/voice members")
            sender.sendMessage("/voice kick <player>")
            sender.sendMessage("/voice transfer <player>")
            return true
        }
        if (sender.hasPermission("sculwave.admin")) {
            sender.sendMessage("§7/voice voiceban <player>")
            sender.sendMessage("§7/voice voiceunban <player>")
            sender.sendMessage("§7/voice voicecheck <player>")
            sender.sendMessage("§7/voice reload")
        }

        when (args[0].lowercase()) {

            "m" -> {
                val settings = plugin.getPlayerSettings(sender)
                settings.muted = !settings.muted
                plugin.updateMuteNameTag(sender)
                sender.sendMessage(
                    if(settings.muted)"§cVoice chat muted."
                    else "§aVoice chat unmuted."
                )

            }

            "invite" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /echo invite <player>")
                    return true
                }
                plugin.invitePlayer(sender, args[1])

            }

            "join" -> {

                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /echo join <code>")
                    return true
                }
                plugin.joinGroup(sender, args[1].uppercase())
            }

            "leave" -> {
                plugin.leaveGroup(sender)
            }

            "members" -> {
                plugin.showMembers(sender)
            }

            "kick" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /echo kick <player>")
                    return true
                }

                plugin.kickPlayer(sender, args[1])
            }

            "transfer" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /echo transfer <player>")
                    return true
                }

                plugin.transferOwnership(sender, args[1])
            }

            "voiceban" -> {

                if (!sender.hasPermission("sculwave.admin")) {
                    sender.sendMessage("§cNo permission.")
                    return true
                }

                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /echo voiceban <player>")
                    return true
                }

                val target = Bukkit.getPlayerExact(args[1])

                if (target == null) {
                    sender.sendMessage("§cPlayer not found.")
                    return true
                }

                plugin.setVoiceBan(target, true)

                Bukkit.broadcast(
                    Component.text("§c[SculWave] ${target.name} has been banned from voice chat.")
                )
            }

            "voiceunban" -> {

                if (!sender.hasPermission("sculwave.admin")) {
                    sender.sendMessage("§cNo permission.")
                    return true
                }

                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /echo voiceunban <player>")
                    return true
                }

                val target = Bukkit.getPlayerExact(args[1])

                if (target == null) {
                    sender.sendMessage("§cPlayer not found.")
                    return true
                }

                plugin.setVoiceBan(target, false)

                Bukkit.broadcast(
                    Component.text("§a[SculWave] ${target.name} has been unbanned from voice chat.")
                )
            }

            "voicecheck" -> {

                if (!sender.hasPermission("sculwave.admin")) {
                    sender.sendMessage("§cNo permission.")
                    return true
                }

                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /echo voicecheck <player>")
                    return true
                }

                val target = Bukkit.getPlayerExact(args[1])

                if (target == null) {
                    sender.sendMessage("§cPlayer not found.")
                    return true
                }

                sender.sendMessage(
                    "§e${target.name}: ${
                        if (plugin.isVoiceBanned(target)) "BANNED"
                        else "ALLOWED"
                    }"
                )
            }

            "reload" -> {

                if (!sender.hasPermission("sculwave.admin")) {
                    sender.sendMessage("§cNo permission.")
                    return true
                }

                plugin.reload(sender)



            }

            else -> {
                sender.sendMessage("§cUnknown subcommand.")
            }
        }

        return true
    }
}