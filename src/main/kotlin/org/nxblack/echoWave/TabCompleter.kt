package org.nxblack.echoWave

import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

class TabCompleter(private val plugin: SculWave) : TabCompleter {

    companion object {

        private val PLAYER_COMMANDS = setOf(
            "mute",
            "unmute",
            "invite",
            "kick",
            "transfer",
            "voiceban",
            "voiceunban",
            "voicecheck"
        )
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): List<String> {

        // First argument
        if (args.size == 1) {

            val commands = mutableListOf(
                "mute",
                "muteall",
                "unmuteall",
                "group",
                "invite",
                "join",
                "leave",
                "members",
                "kick",
                "transfer"
            )

            if (sender.hasPermission("sculwave.admin")) {
                commands += listOf(
                    "voiceban",
                    "voiceunban",
                    "voicecheck",
                    "reload"
                )
            }

            return commands
                .filter {
                    it.startsWith(
                        args[0],
                        ignoreCase = true
                    )
                }
        }

        // Second argument
        if (args.size == 2) {

            when (args[0].lowercase()) {

                "join" -> {
                    val player = sender as? Player ?: return emptyList()

                    return plugin.pendingInvites[player.uniqueId]
                        ?.map { it.code }
                        ?.filter {
                            it.startsWith(
                                args[1],
                                ignoreCase = true
                            )
                        }
                        ?: emptyList()
                }

                in PLAYER_COMMANDS -> {
                    return Bukkit.getOnlinePlayers()
                        .map { it.name }
                        .filter {
                            it.startsWith(
                                args[1],
                                ignoreCase = true
                            )
                        }
                }
            }
        }

        return emptyList()
    }
}