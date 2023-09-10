package net.azisaba.itemstash.command;

import net.azisaba.itemstash.ItemStashPlugin;
import net.azisaba.itemstash.sql.DBConnector;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.Collections;
import java.util.List;

public class StashNotifyCommand implements TabExecutor {
    private final ItemStashPlugin plugin;

    public StashNotifyCommand(@NotNull ItemStashPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            return true;
        }
        Player player = (Player) sender;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean current = DBConnector.isSuppressNotification(player.getUniqueId());
                DBConnector.setSuppressNotification(player.getUniqueId(), !current);
                if (current) {
                    // don't suppress (suppress_notification = false)
                    player.sendMessage(ChatColor.GREEN + "Stash通知をオンにしました。");
                } else {
                    // do suppress (suppress_notification = true)
                    player.sendMessage(ChatColor.GREEN + "Stash通知をオフにしました。");
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
