package net.azisaba.itemstash.command;

import net.azisaba.itemstash.ItemStash;
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
        if (args.length == 1 && args[0].equalsIgnoreCase("nogui")) {
            if (PROCESSING.contains(player.getUniqueId())) {
                player.sendMessage(ChatColor.RED + "前回の処理が継続中です。しばらくしてからお試しください。(Local)");
                return true;
            }
            PROCESSING.add(player.getUniqueId());
            Bukkit.getScheduler().runTaskAsynchronously((ItemStashPlugin) ItemStash.getInstance(), () -> {
                try {
                    try {
                        if (DBConnector.isOperationInProgress(player.getUniqueId())) {
                            player.sendMessage(ChatColor.RED + "前回の処理が継続中です。しばらくしてからお試しください。(Server)");
                            return;
                        }
                        player.sendMessage(ChatColor.GRAY + "処理中です...");
                        DBConnector.setOperationInProgress(player.getUniqueId(), true);
                        long start = System.currentTimeMillis();
                        int count = plugin.getStashItemCount(player.getUniqueId());
                        if (count == 0) {
                            long total = System.currentTimeMillis() - start;
                            player.sendMessage(ChatColor.RED + "Stashは空です。" + ChatColor.DARK_GRAY + " [" + total + "ms]");
                            return;
                        }
                        plugin.dumpStash(player).thenAccept(result -> {
                            long total = System.currentTimeMillis() - start;
                            if (result && count <= 100) {
                                player.sendMessage(ChatColor.GREEN + "アイテムをすべて受け取りました。(処理前の件数: " + count + ")" + ChatColor.DARK_GRAY + " [" + total + "ms]");
                            } else {
                                player.sendMessage(ChatColor.RED + "一部のアイテムを受け取れませんでした。(処理前の件数: " + count + ")" + ChatColor.DARK_GRAY + " [" + total + "ms]");
                            }
                        });
                    } finally {
                        PROCESSING.remove(player.getUniqueId());
                        DBConnector.setOperationInProgress(player.getUniqueId(), false);
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });
            return true;
        }
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
