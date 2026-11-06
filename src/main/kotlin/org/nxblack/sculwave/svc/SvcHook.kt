package org.nxblack.sculwave.svc

import de.maxhenkel.voicechat.api.BukkitVoicechatService
import org.bukkit.plugin.java.JavaPlugin

/**
 * The only entry point that touches the Simple Voice Chat API from the rest of the plugin.
 * It is first used from inside a try/catch in [SvcBridge.register], and only after we have
 * confirmed that the "voicechat" plugin is installed.
 */
internal object SvcHook {

    fun register(plugin: JavaPlugin, host: SvcHost, bridge: SvcBridge): SvcPlayback? {
        val service = plugin.server.servicesManager.load(BukkitVoicechatService::class.java) ?: return null
        val voicechatPlugin = SculWaveVoicechatPlugin(host, bridge)
        service.registerPlugin(voicechatPlugin)
        return voicechatPlugin
    }
}
