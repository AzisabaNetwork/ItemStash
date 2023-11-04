package net.azisaba.itemstash.listener;

import net.azisaba.itemstash.ItemStashPlugin;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class JoinListener implements Listener {
    private final ItemStashPlugin plugin;

    public JoinListener(ItemStashPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> plugin.forceNotifyStash(e.getPlayer()), 20 * 2);
    }
}
