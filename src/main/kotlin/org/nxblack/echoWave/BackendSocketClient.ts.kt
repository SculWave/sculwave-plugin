package org.nxblack.echoWave

import io.socket.client.IO
import io.socket.client.Socket
import io.socket.engineio.client.transports.WebSocket
import org.bukkit.plugin.java.JavaPlugin
import org.json.JSONArray
import org.json.JSONObject
import org.nxblack.echoWave.data.BackendState
import org.nxblack.echoWave.data.PositionPayload

class BackendSocketClient(
    private val plugin: JavaPlugin,
    private val serverUrl: String,
    private val backendId: String,
    private val instanceId: String,
) {

    private lateinit var socket: Socket

    private var authenticated = false
    private var authToken: String? = null

    fun connect() {
        if (::socket.isInitialized) {
            socket.off()
            socket.disconnect()
        }

        val options = IO.Options.builder()
            .setReconnection(true)
            .setReconnectionAttempts(Int.MAX_VALUE)
            .setReconnectionDelay(2000)
            .build()

        options.path = "/socket.io"
        options.transports = arrayOf(WebSocket.NAME)

        socket = IO.socket(
            "$serverUrl/backend",
            options
        )

        socket.on(Socket.EVENT_CONNECT) {

            plugin.logger.info(
                "[BackendSocket] Connected (${socket.id()})"
            )

            plugin.logger.info(
                "[BackendSocket] Requesting backend registration..."
            )

            socket.emit(
                "backend-register",
                JSONObject()
                    .put("backendId", backendId)
                    .put("instanceId", instanceId)
            )

        }

        socket.on("backend-register-success") { args ->

            val json = args[0] as JSONObject

            val roomCode = json.getString("roomCode")
            val token = json.getString("token")

            authToken = token

            BackendState.roomCode = roomCode
            BackendState.authToken = token

            plugin.logger.info("====================================")
            plugin.logger.info("EchoWave room created successfully!")
            plugin.logger.info("Room Code: $roomCode")
            plugin.logger.info("====================================")

            socket.emit(
                "backend-auth",
                JSONObject()
                    .put("backendId", backendId)
                    .put("instanceId", instanceId)
                    .put("token", token)
            )

        }

        socket.on("backend-register-failed") { args ->

            plugin.logger.severe(
                "[BackendSocket] Registration failed: ${args.firstOrNull()}"
            )
            authenticated = false
            socket.disconnect()

        }

        socket.on("backend-auth-success") {

            authenticated = true

            plugin.logger.info(
                "[BackendSocket] Authenticated"
            )

        }

        socket.on("backend-auth-failed") { args ->

            authenticated = false
            socket.disconnect()
            plugin.logger.severe(
                "[BackendSocket] Authentication failed: ${args.firstOrNull()}"
            )

        }

        socket.on(Socket.EVENT_DISCONNECT) { args ->

            authenticated = false
            authToken = null

            plugin.logger.warning(
                "[BackendSocket] Disconnected: ${args.firstOrNull()}"
            )

        }


        socket.on(Socket.EVENT_CONNECT_ERROR) { args ->

            authenticated = false

            plugin.logger.severe(
                "[BackendSocket] Connect error: ${args.firstOrNull()}"
            )

        }

        val manager = socket.io()

        manager.on(io.socket.client.Manager.EVENT_RECONNECT) {
            plugin.logger.info("Reconnected")
        }

        manager.on(io.socket.client.Manager.EVENT_RECONNECT_ATTEMPT) {
            plugin.logger.info("Reconnect attempt")
        }

        socket.connect()

    }

    fun isAuthenticated(): Boolean {

        return authenticated

    }

    fun disconnect() {

        if (!::socket.isInitialized)
            return

        if (socket.connected()) {

            socket.emit("backend-shutdown")

            socket.close()

        }


    }

    fun sendHeartbeat(
        players: List<PositionPayload>,
        voiceEnabled: Boolean
    ) {

        if (!authenticated || !socket.connected()) {
            return
        }

        val playerArray = JSONArray()

        players.forEach { player ->

            playerArray.put(
                JSONObject()
                    .put("gamertag", player.gamertag)
                    .put("x", player.x)
                    .put("y", player.y)
                    .put("z", player.z)
                    .put("dimension", player.dimension)
                    .put("groupId", player.groupId)
                    .put("isMuted", player.isMuted)
            )

        }

        socket.emit(
            "backend-heartbeat",
            JSONObject()
                .put("players", playerArray)
                .put("timestamp", System.currentTimeMillis())
                .put("voiceEnabled", voiceEnabled)
        )

    }

}