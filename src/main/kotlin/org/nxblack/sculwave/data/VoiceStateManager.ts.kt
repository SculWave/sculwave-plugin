package org.nxblack.sculwave.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

data class VoicePlayerState(
    val roomCode: String,
    val gamertag: String,
    val connected: Boolean,
    val muted: Boolean,
    val speaking: Boolean,
    val timestamp: Long,
    /** True when this "participant" is an SVC player bridged into the room, not a real app user. */
    val bridged: Boolean = false
)

// Written from the socket thread, read from the main thread and from Simple Voice Chat's
// audio thread, so the collections must be thread-safe.
class VoiceStateManager {
    private val states = ConcurrentHashMap<String, VoicePlayerState>()
    private val listeners = CopyOnWriteArraySet<(VoicePlayerState) -> Unit>()
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
