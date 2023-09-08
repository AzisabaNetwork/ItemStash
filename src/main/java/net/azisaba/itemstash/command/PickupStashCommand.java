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
    public /* internal */ static final Set<UUID> PROCESSING = Collections.synchronizedSet(new HashSet<>());
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
        UUID targetUUID = player.getUniqueId();
        if (sender.hasPermission("itemstash.others") && args.length >= 1) {
            targetUUID = UUID.fromString(args[0]);
        }
        if (PROCESSING.contains(targetUUID)) {
            player.sendMessage(ChatColor.RED + "前回の処理が継続中です。しばらくしてからお試しください。(Local)");
            return true;
        }
        PROCESSING.add(targetUUID);
        UUID finalTargetUUID = targetUUID;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection connection = DBConnector.getConnection()) {
                if (DBConnector.isOperationInProgress(finalTargetUUID)) {
                    player.sendMessage(ChatColor.RED + "前回の処理が継続中です。しばらくしてからお試しください。(Server)");
                    return;
                }
                player.sendMessage(ChatColor.GRAY + "処理中です...");
                List<Map.Entry<ItemStack, Long>> items = new ArrayList<>();
                try (PreparedStatement stmt = connection.prepareStatement("SELECT `item`, `expires_at`, `true_amount` FROM `stashes` WHERE `uuid` = ?")) {
                    stmt.setString(1, finalTargetUUID.toString());
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            Blob blob = rs.getBlob("item");
                            byte[] bytes = blob.getBytes(1, (int) blob.length());
                            long expiresAt = rs.getLong("expires_at");
                            int trueAmount = rs.getInt("true_amount");
                            ItemStack item = ItemStack.deserializeBytes(bytes);
                            if (trueAmount > 0) {
                                item.setAmount(trueAmount);
                            }
                            //plugin.getLogger().info("Item: " + item);
                            items.add(new AbstractMap.SimpleImmutableEntry<>(item, expiresAt));
                        }
                    }
                }
                Bukkit.getScheduler().runTask(plugin, () -> player.openInventory(new PickupStashScreen(items).getInventory()));
            } catch (SQLException e) {
                throw new RuntimeException(e);
            } finally {
                PROCESSING.remove(finalTargetUUID);
            }
        });
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
