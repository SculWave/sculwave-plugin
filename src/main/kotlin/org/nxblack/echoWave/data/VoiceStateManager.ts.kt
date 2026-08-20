package org.nxblack.echoWave.data

data class VoicePlayerState(
    val roomCode: String,
    val gamertag: String,
    val connected: Boolean,
    val muted: Boolean,
    val speaking: Boolean,
    val timestamp: Long
)

class VoiceStateManager {
    private val states = mutableMapOf<String, VoicePlayerState>()
    private val listeners = mutableSetOf<(VoicePlayerState) -> Unit>()
    fun update(state: VoicePlayerState) {
        states[state.gamertag.lowercase()] = state

        for (listener in listeners) {
            listener(state)
        }
    }

    fun get(gamertag: String): VoicePlayerState? {
        return states[gamertag.lowercase()]
    }

    fun remove(gamertag: String) {
        states.remove(gamertag.lowercase())
    }

    fun all(): Collection<VoicePlayerState> {
        return states.values
    }

    fun clear() {
        states.clear()
    }
    fun subscribe(listener: (VoicePlayerState) -> Unit) {
        listeners.add(listener)
    }
}