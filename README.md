# Sculwave

**Sculwave** is a lightweight proximity voice integration for Minecraft that connects Java Edition, Bedrock Edition (via Geyser), and the EchoWave mobile application.


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
* 🔄 Automatic room recreation if connection loss restarts

---

## Supported Servers

* Paper
* Purpur *(Beta)*
* Pufferfish *(Beta)*
* Spigot *(Beta)*
* Folia 

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
| `/vc`                   | Shows help                      |
| `/vm`              | Toggle voice chat               |
| `/vc invite <player>`   | Invite a player to your group   |
| `/vc join <code>`       | Join a group                    |
| `/vc leave`             | Leave your current group        |
| `/vc members`           | View group members              |
| `/vc kick <player>`     | Remove a member from your group |
| `/vc transfer <player>` | Transfer group ownership        |

### Administrator Commands

| Command                     | Description                            |
| --------------------------- | -------------------------------------- |
| `/vc voiceban <player>`   | Prevent a player from using voice chat |
| `/vc voiceunban <player>` | Remove a voice ban                     |
| `/vc voicecheck <player>` | Check a player's voice status          |
| `/vc reload`              | Reload the configuration               |

---

## Permissions

| Permission       | Description                          |
| ---------------- | ------------------------------------ |
| `sculwave.admin` | Access to all administrator commands |

Default: **OP**

---

## Configuration

```yaml

# SculWave backend URL
server-url: "https://sculwave.com"
#url to your self-hosted sculwave server if you're not using the sculwave cloud service

# Your unique activation key from the SculWave Dashboard.
# The plugin will automatically generate and cache the
# instance ID and backend ID after successful activation.
activation-key: ""



# Automatically managed by SculWave.
# Do not edit these values manually.
instance-id: ""
backend-id: ""

# Enables or disables voice chat for this Minecraft server.
#
# true  - Players receive LiveKit voice and can communicate.
# false - Players remain connected to the SculWave network and
#         automatic server switching still works, but no voice
#         session is created while they are on this server.
#
# This is useful for hubs, lobbies, AFK servers, minigames,
# or any server where voice chat should be disabled.
voice-enabled: true

#entry-point: false — normal backend by default.
#entry-point: true — advertise this backend as a network entry point.
#entry-point-priority: 1 — preferred entry point; 2, 3, etc. are fallbacks.
#

entry-point: false
entry-point-priority: 1



# ==========================================
# Voice Moderation
# ==========================================

# UUIDs of players permanently banned from using SculWave voice.
# Managed automatically by in-game moderation commands.

voice-bans: []

# ==========================================
# Sound Effects
# ==========================================

sounds:
  enabled: true

  join: entity.player.levelup
  leave: entity.experience_orb.pickup
  invite: block.note_block.chime
  invite-sent: block.note_block.bell
  kick: entity.villager.no
  transfer: ui.toast.challenge_complete

# ==========================================
# Debug
# ==========================================

# Enables additional diagnostic logging.
debug: false
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

Sculwave fully supports Bedrock players through:

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

making Sculwave suitable for multi-world and networked servers.

---

## Voice Bans

Administrators can permanently prevent players from using Sculwave.

Voice bans persist across server restarts.

---

## Companion App

When players join the server, they receive the generated room code.

Simply open the Sculwave app, enter the room code, and connect.

---

## Backend

Sculwave communicates with a secure backend service that:

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
