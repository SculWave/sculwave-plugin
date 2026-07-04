# EchoWave

**EchoWave** is a lightweight proximity voice integration for Minecraft that connects Java Edition, Bedrock Edition (via Geyser), and the EchoWave mobile application.

Unlike traditional voice chat mods, EchoWave requires **no client-side Java mod**. Players simply join the server, enter the room code shown by the plugin into the EchoWave app, and begin talking with nearby players.

---

## Features

* 🎙️ Real-time proximity voice chat
* 📱 Companion mobile application
* ☕ Supports Java Edition servers
* 🛏️ Supports Bedrock Edition through Geyser/Floodgate
* 👥 Party (Group) voice system
* 🔇 Individual mute toggle
* 🔨 Voice bans for administrators
* 🔊 Configurable sounds
* ⚡ Lightweight HTTP communication
* 🌍 Multi-world support
* 🔄 Automatic room recreation if the backend restarts

---

## Supported Servers

* Paper
* Purpur
* Pufferfish
* Spigot
* Folia *(experimental)*

---

## Installation

1. Download the latest EchoWave plugin.
2. Place the plugin inside your server's `plugins` folder.
3. Start the server.
4. A room code will be generated automatically.
5. Players join using the EchoWave mobile application.

---

## Commands

### Player Commands

| Command                   | Description                     |
| ------------------------- | ------------------------------- |
| `/echo`                   | Shows help                      |
| `/echo mute`              | Toggle voice chat               |
| `/echo invite <player>`   | Invite a player to your group   |
| `/echo join <code>`       | Join a group                    |
| `/echo leave`             | Leave your current group        |
| `/echo members`           | View group members              |
| `/echo kick <player>`     | Remove a member from your group |
| `/echo transfer <player>` | Transfer group ownership        |

### Administrator Commands

| Command                     | Description                            |
| --------------------------- | -------------------------------------- |
| `/echo voiceban <player>`   | Prevent a player from using voice chat |
| `/echo voiceunban <player>` | Remove a voice ban                     |
| `/echo voicecheck <player>` | Check a player's voice status          |
| `/echo reload`              | Reload the configuration               |

---

## Permissions

| Permission       | Description                          |
| ---------------- | ------------------------------------ |
| `echowave.admin` | Access to all administrator commands |

Default: **OP**

---

## Configuration

```yaml
voice-bans: []

server-id: survival

sounds:
  enabled: true

  join: entity.player.levelup
  invite: block.note_block.chime
  invite-sent: block.note_block.bell
  kick: entity.villager.no
  transfer: ui.toast.challenge_complete
```

---

## Group System

Groups allow players to communicate regardless of proximity.

Features include:

* Automatic group creation when inviting
* Group invitations
* Ownership transfer
* Member kicking
* Automatic owner transfer when the owner leaves
* Instant group synchronization

---

## Bedrock Support

EchoWave fully supports Bedrock players through:

* Geyser
* Floodgate

Bedrock players use the same mobile application and can communicate seamlessly with Java players.

---

## Multi-World Support

Players only hear others within the same world.

This prevents voice chat between:

* Hub ↔ Survival
* Survival ↔ Skyblock
* Lobby ↔ Minigames

making EchoWave suitable for multi-world and networked servers.

---

## Voice Bans

Administrators can permanently prevent players from using EchoWave.

Voice bans persist across server restarts.

---

## Companion App

When players join the server, they receive the generated room code.

Simply open the EchoWave app, enter the room code, and connect.

---

## Backend

EchoWave communicates with a secure backend service that:

* Maintains active voice rooms
* Receives player position updates
* Calculates nearby players
* Automatically recreates rooms if necessary

---

## Requirements

* Java 21 or newer
* Minecraft 1.21+
* Internet connection for backend communication

---

## License

Copyright © EchoWave.

All rights reserved.
