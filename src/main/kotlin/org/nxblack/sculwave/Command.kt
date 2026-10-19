package org.nxblack.sculwave

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

        // "m" is the short form of "mute"
        val subcommand = if (command.name.equals("vm", ignoreCase = true)) {
            "mute"
        } else {
            args.firstOrNull()?.lowercase()?.let { if (it == "m") "mute" else it }
        }

        if (plugin.isVoiceBanned(sender) &&
            subcommand !in setOf("voiceban", "voiceunban", "voicecheck")) {
            sender.sendMessage("§c[SculWave] You are banned from voice chat.")
            return true
        }

        if (subcommand == null) {
            sender.sendMessage("§6[SculWave] Voice Commands")
            sender.sendMessage("§7/vm §8- toggle your mute")
            sender.sendMessage("§7/vc invite <player>")
            sender.sendMessage("§7/vc join <code>")
            sender.sendMessage("§7/vc leave")
            sender.sendMessage("§7/vc members")
            sender.sendMessage("§7/vc kick <player>")
            sender.sendMessage("§7/vc transfer <player>")
            if (sender.hasPermission("sculwave.admin")) {
                sender.sendMessage("§7/vc voiceban <player>")
                sender.sendMessage("§7/vc voiceunban <player>")
                sender.sendMessage("§7/vc voicecheck <player>")
                sender.sendMessage("§7/vc reload")
            }
            return true
        }

        when (subcommand) {

            "mute" -> {
                val settings = plugin.getPlayerSettings(sender)
                settings.muted = !settings.muted
                plugin.updateMuteNameTag(sender)
                sender.sendMessage(
                    if (settings.muted) "§cVoice chat muted." else "§aVoice chat unmuted."
                )
            }

            "invite" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /vc invite <player>")
                    return true
                }
                plugin.invitePlayer(sender, args[1])
            }

            "join" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /vc join <code>")
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
                    sender.sendMessage("§cUsage: /vc kick <player>")
                    return true
                }
                plugin.kickPlayer(sender, args[1])
            }

            "transfer" -> {
                if (args.size < 2) {
                    sender.sendMessage("§cUsage: /vc transfer <player>")
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
                    sender.sendMessage("§cUsage: /vc voiceban <player>")
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
                    sender.sendMessage("§cUsage: /vc voiceunban <player>")
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
                    sender.sendMessage("§cUsage: /vc voicecheck <player>")
                    return true
                }
                val target = Bukkit.getPlayerExact(args[1])
                if (target == null) {
                    sender.sendMessage("§cPlayer not found.")
                    return true
                }
                sender.sendMessage(
                    "§e${target.name}: ${if (plugin.isVoiceBanned(target)) "BANNED" else "ALLOWED"}"
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