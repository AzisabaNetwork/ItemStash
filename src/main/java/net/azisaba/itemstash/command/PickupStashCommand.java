package net.azisaba.itemstash.command;

import net.azisaba.itemstash.ItemStashPlugin;
import net.azisaba.itemstash.gui.PickupStashScreen;
import net.azisaba.itemstash.sql.DBConnector;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.*;
import java.util.*;

public class PickupStashCommand implements TabExecutor {
    private static final Set<UUID> PROCESSING = Collections.synchronizedSet(new HashSet<>());
    private final ItemStashPlugin plugin;

    public PickupStashCommand(@NotNull ItemStashPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("このコマンドはコンソールから実行できません。");
            return true;
        }
        Player player = (Player) sender;
        if (PROCESSING.contains(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "前回の処理が継続中です。しばらくしてからお試しください。");
            return true;
        }
        UUID targetUUID = player.getUniqueId();
        if (sender.hasPermission("itemstash.others") && args.length >= 1) {
            targetUUID = UUID.fromString(args[0]);
        }
        PROCESSING.add(player.getUniqueId());
        player.sendMessage(ChatColor.GRAY + "処理中です...");
        UUID finalTargetUUID = targetUUID;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection connection = DBConnector.getConnection()) {
                List<Map.Entry<ItemStack, Long>> items = new ArrayList<>();
                try (PreparedStatement stmt = connection.prepareStatement("SELECT `item`, `expires_at` FROM `stashes` WHERE `uuid` = ? ORDER BY IF(`expires_at` = -1, 1, 0), `expires_at`")) {
                    stmt.setString(1, finalTargetUUID.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            Blob blob = rs.getBlob("item");
                            byte[] bytes = blob.getBytes(1, (int) blob.length());
                            long expiresAt = rs.getLong("expires_at");
                            items.add(new AbstractMap.SimpleImmutableEntry<>(ItemStack.deserializeBytes(bytes), expiresAt));
                        }
                    }
                }
                Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(new PickupStashScreen(items).getInventory()));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } finally {
                PROCESSING.remove(player.getUniqueId());
            }
        });
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
