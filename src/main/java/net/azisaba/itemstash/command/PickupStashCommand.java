package net.azisaba.itemstash.command;

import net.azisaba.itemstash.ItemStash;
import net.azisaba.itemstash.ItemStashPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class PickupStashCommand implements TabExecutor {
    private static final Set<UUID> PROCESSING = Collections.synchronizedSet(new HashSet<>());
    private final ItemStash itemStash;

    public PickupStashCommand(@NotNull ItemStash itemStash) {
        this.itemStash = itemStash;
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
        PROCESSING.add(player.getUniqueId());
        player.sendMessage(ChatColor.GRAY + "処理中です...");
        Bukkit.getScheduler().runTaskAsynchronously((ItemStashPlugin) ItemStash.getInstance(), () -> {
            try {
                long start = System.currentTimeMillis();
                int count = itemStash.getStashItemCount(player.getUniqueId());
                if (count == 0) {
                    long total = System.currentTimeMillis() - start;
                    player.sendMessage(ChatColor.RED + "Stashは空です。" + ChatColor.DARK_GRAY + " [" + total + "ms]");
                    return;
                }
                itemStash.dumpStash(player).thenAccept(result -> {
                    long total = System.currentTimeMillis() - start;
                    if (result) {
                        player.sendMessage(ChatColor.GREEN + "アイテムをすべて受け取りました。" + ChatColor.DARK_GRAY + " [" + total + "ms]");
                    } else {
                        player.sendMessage(ChatColor.RED + "一部のアイテムを受け取れませんでした。" + ChatColor.DARK_GRAY + " [" + total + "ms]");
                    }
                });
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
