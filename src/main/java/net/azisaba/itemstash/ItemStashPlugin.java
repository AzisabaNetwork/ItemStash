package net.azisaba.itemstash;

import net.azisaba.itemstash.command.PickupStashCommand;
import net.azisaba.itemstash.sql.DBConnector;
import net.azisaba.itemstash.sql.DatabaseConfig;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public class ItemStashPlugin extends JavaPlugin implements ItemStash {
    @Override
    public void onEnable() {
        DatabaseConfig databaseConfig = new DatabaseConfig(Objects.requireNonNull(getConfig().getConfigurationSection("database"), "database"));
        try {
            DBConnector.init(databaseConfig);
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        Objects.requireNonNull(Bukkit.getPluginCommand("pickupstash"))
                .setExecutor(new PickupStashCommand(this));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                int count = getStashItemCount(player.getUniqueId());
                if (count == 0) {
                    continue;
                }
                player.sendMessage(ChatColor.GOLD + "Stashに" + ChatColor.RED + count + ChatColor.GOLD + "個のアイテムがあります！");
                player.sendMessage(ChatColor.GOLD + "受け取るには" + ChatColor.AQUA + "/pickupstash" + ChatColor.GOLD + "を実行してください。");
            }
        }, 20 * 60 * 5, 20 * 60 * 5);
    }

    @Override
    public void onDisable() {
        DBConnector.close();
    }

    @Override
    public void addItemToStash(@NotNull UUID player, @NotNull ItemStack itemStack) {
        try {
            DBConnector.runPrepareStatement("INSERT INTO `stashes` (`uuid`, `item`) VALUES (?, ?)", statement -> {
                statement.setString(1, player.toString());
                statement.setBytes(2, itemStack.serializeAsBytes());
                statement.executeUpdate();
            });
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public int getStashItemCount(@NotNull UUID player) {
        try {
            return DBConnector.getPrepareStatement("SELECT COUNT(*) FROM `stashes` WHERE `uuid` = ?", statement -> {
                statement.setString(1, player.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                    return 0;
                }
            });
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean dumpStash(@NotNull Player player) {
        List<ItemStack> items = new ArrayList<>();
        try {
            DBConnector.runPrepareStatement("SELECT `item` FROM `stashes` WHERE `uuid` = ?", statement -> {
                statement.setString(1, player.getUniqueId().toString());
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        items.add(ItemStack.deserializeBytes(rs.getBytes("item")));
                    }
                }
            });
            DBConnector.runPrepareStatement("DELETE FROM `stashes` WHERE `uuid` = ?", statement -> {
                statement.setString(1, player.getUniqueId().toString());
                statement.executeUpdate();
            });
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        //noinspection ConstantValue
        if (items.isEmpty()) {
            return true;
        }
        Collection<ItemStack> notFit = player.getInventory().addItem(items.toArray(new ItemStack[0])).values();
        notFit.forEach((itemStack) -> addItemToStash(player.getUniqueId(), itemStack));
        return notFit.isEmpty();
    }
}
