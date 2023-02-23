package net.azisaba.itemstash.command;

import net.azisaba.itemstash.ItemStash;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

public class PickupStashCommand implements TabExecutor {
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
        int count = itemStash.getStashItemCount(player.getUniqueId());
        if (count == 0) {
            player.sendMessage(ChatColor.RED + "Stashは空です。");
            return true;
        }
        itemStash.dumpStash(player).thenAccept(result -> {
            if (result) {
                player.sendMessage(ChatColor.GREEN + "アイテムをすべて受け取りました。");
            } else {
                player.sendMessage(ChatColor.RED + "一部のアイテムを受け取れませんでした。");
            }
        });
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return Collections.emptyList();
    }
}
