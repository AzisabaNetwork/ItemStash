package net.azisaba.itemstash.command;

import net.azisaba.itemstash.ItemStashPlugin;
import net.azisaba.itemstash.sql.DBConnector;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.mariadb.jdbc.MariaDbBlob;

import java.sql.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ItemStashCommand implements TabExecutor {
    private final ItemStashPlugin plugin;

    public ItemStashCommand(@NotNull ItemStashPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "aaaa");
            return true;
        }
        if (args[0].equals("add")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "you're not a player");
                return true;
            }
            Player senderPlayer = (Player) sender;
            ItemStack stack = senderPlayer.getInventory().getItemInMainHand();
            if (stack.getType() == Material.AIR || stack.getAmount() == 0) {
                sender.sendMessage(ChatColor.RED + "you are not holding an item");
                return true;
            }
            if (args.length == 1) {
                sender.sendMessage(ChatColor.RED + "/itemstash add <player> [count]");
                return true;
            }
            Player player = Bukkit.getPlayerExact(args[1]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "no such player: " + args[1]);
                return true;
            }
            int count = args.length == 2 ? 1 : Integer.parseInt(args[2]);
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                for (int i = 0; i < count; i++) {
                    plugin.addItemToStash(player.getUniqueId(), stack);
                }
                sender.sendMessage(ChatColor.GREEN + "ﾖｼ!");
            });
        } else if (args[0].equals("count")) {
            if (args.length == 1) {
                sender.sendMessage(ChatColor.RED + "/itemstash count <player>");
                return true;
            }
            Player player = Bukkit.getPlayerExact(args[1]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "no such player: " + args[1]);
                return true;
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                int count = plugin.getStashItemCount(player.getUniqueId());
                sender.sendMessage(ChatColor.RED + player.getName() + ChatColor.GOLD + " has " + ChatColor.GREEN + count + ChatColor.GOLD + " items in stash");
            });
        } else if (args[0].equals("removeSimilar")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "you're not a player");
                return true;
            }
            Player senderPlayer = (Player) sender;
            ItemStack stack = senderPlayer.getInventory().getItemInMainHand();
            if (stack.getType() == Material.AIR || stack.getAmount() == 0) {
                sender.sendMessage(ChatColor.RED + "you are not holding an item");
                return true;
            }
            if (args.length == 1) {
                sender.sendMessage(ChatColor.RED + "/itemstash removeSimilar <player>");
                return true;
            }
            Player player = Bukkit.getPlayerExact(args[1]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "no such player: " + args[1]);
                return true;
            }
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try (Connection connection = DBConnector.getConnection()) {
                    List<byte[]> toRemove = new ArrayList<>();
                    try (PreparedStatement stmt = connection.prepareStatement("SELECT `item` FROM `stashes` WHERE `uuid` = ? ORDER BY IF(`expires_at` = -1, 1, 0), `expires_at`")) {
                        stmt.setString(1, player.getUniqueId().toString());
                        try (ResultSet rs = stmt.executeQuery()) {
                            while (rs.next()) {
                                Blob blob = rs.getBlob("item");
                                byte[] bytes = blob.getBytes(1, (int) blob.length());
                                if (ItemStack.deserializeBytes(bytes).isSimilar(stack)) {
                                    toRemove.add(bytes);
                                }
                            }
                        }
                    }
                    try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM `stashes` WHERE `uuid` = ? AND `item` = ? ORDER BY IF(`expires_at` = -1, 1, 0), `expires_at` LIMIT 1")) {
                        for (byte[] bytes : toRemove) {
                            stmt.setString(1, player.getUniqueId().toString());
                            stmt.setBlob(2, new MariaDbBlob(bytes));
                            stmt.executeUpdate();
                        }
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                sender.sendMessage(ChatColor.GREEN + "ﾖｼ!");
            });
        }
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return Stream.of("add", "count", "removeSimilar").filter(s -> s.startsWith(args[0])).collect(Collectors.toList());
        }
        if (args.length == 2) {
            if (args[0].equals("add") || args[0].equals("count") || args[0].equals("removeSimilar")) {
                return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).collect(Collectors.toList());
            }
        }
        return Collections.emptyList();
    }
}
