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

import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
            pickupWithoutGui(player);
            return true;
        }

        UUID targetUUID = player.getUniqueId();
        if (sender.hasPermission("itemstash.others") && args.length >= 1) {
            targetUUID = UUID.fromString(args[0]);
        }
        if (!PROCESSING.add(targetUUID)) {
            player.sendMessage(ChatColor.RED + "前回の処理が継続中です。しばらくしてからお試しください。(Local)");
            return true;
        }
        UUID finalTargetUUID = targetUUID;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection connection = DBConnector.getConnection()) {
                if (DBConnector.isOperationInProgress(finalTargetUUID)) {
                    sendMessage(player, ChatColor.RED + "前回の処理が継続中です。しばらくしてからお試しください。(Server)");
                    return;
                }
                sendMessage(player, ChatColor.GRAY + "処理中です...");
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
        if (args.length == 1 && sender instanceof Player) {
            return Collections.singletonList("nogui");
        }
        return Collections.emptyList();
    }

    private void pickupWithoutGui(@NotNull Player player) {
        UUID uuid = player.getUniqueId();
        if (!PROCESSING.add(uuid)) {
            player.sendMessage(ChatColor.RED + "前回の処理が継続中です。しばらくしてからお試しください。(Local)");
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (DBConnector.isOperationInProgress(uuid)) {
                    sendMessage(player, ChatColor.RED + "前回の処理が継続中です。しばらくしてからお試しください。(Server)");
                    PROCESSING.remove(uuid);
                    return;
                }
                DBConnector.setOperationInProgress(uuid, true);
            } catch (SQLException e) {
                PROCESSING.remove(uuid);
                throw new RuntimeException(e);
            }

            sendMessage(player, ChatColor.GRAY + "処理中です...");
            long start = System.currentTimeMillis();
            plugin.dumpStash(player).whenComplete((result, throwable) -> Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    if (throwable != null) {
                        plugin.getSLF4JLogger().error("Failed to dump stash for {}", player.getUniqueId(), throwable);
                        sendMessage(player, ChatColor.RED + "アイテムの受け取り中にエラーが発生しました。");
                        return;
                    }

                    long total = System.currentTimeMillis() - start;
                    if (result) {
                        sendMessage(player, ChatColor.GREEN + "アイテムをすべて受け取りました。" + ChatColor.DARK_GRAY + " [" + total + "ms]");
                    } else {
                        sendMessage(player, ChatColor.RED + "一部のアイテムを受け取れませんでした。" + ChatColor.DARK_GRAY + " [" + total + "ms]");
                    }
                } finally {
                    PROCESSING.remove(uuid);
                    try {
                        DBConnector.setOperationInProgress(uuid, false);
                    } catch (SQLException e) {
                        plugin.getSLF4JLogger().error("Failed to clear operation_in_progress state for {}", player.getUniqueId(), e);
                    }
                }
            }));
        });
    }

    private void sendMessage(@NotNull Player player, @NotNull String message) {
        Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(message));
    }
}
