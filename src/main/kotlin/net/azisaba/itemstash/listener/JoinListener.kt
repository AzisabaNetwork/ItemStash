package net.azisaba.itemstash.listener

import net.azisaba.itemstash.ItemStashPlugin
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class JoinListener(private val plugin: ItemStashPlugin) : Listener {
    @EventHandler
    fun onPlayerJoin(e: PlayerJoinEvent) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, Runnable { plugin.forceNotifyStash(e.player) }, 20L * 2)
    }
}
