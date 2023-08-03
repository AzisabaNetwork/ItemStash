package net.azisaba.itemstash;

import net.azisaba.itemstash.command.PickupStashCommand;
import net.azisaba.itemstash.sql.DBConnector;
import net.azisaba.itemstash.sql.DatabaseConfig;
import net.azisaba.itemstash.util.ItemUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.mariadb.jdbc.MariaDbBlob;

import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class ItemStashPlugin extends JavaPlugin implements ItemStash {
    private final Executor sync = r -> Bukkit.getScheduler().runTask(this, r);
    private final Executor async = r -> Bukkit.getScheduler().runTaskAsynchronously(this, r);

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
            getLogger().info("Adding item to stash of " + player + ":");
            ItemUtil.log(getLogger(), itemStack);
            DBConnector.runPrepareStatement("INSERT INTO `stashes` (`uuid`, `item`) VALUES (?, ?)", statement -> {
                statement.setString(1, player.toString());
                statement.setBlob(2, new MariaDbBlob(itemStack.serializeAsBytes()));
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
    public CompletableFuture<Boolean> dumpStash(@NotNull Player player) {
        return CompletableFuture.supplyAsync(() -> {
            List<ItemStack> items = new ArrayList<>();
            List<byte[]> byteList = new ArrayList<>();
            try {
                try (Connection connection = DBConnector.getConnection()) {
                    Statement statement = connection.createStatement();
                    statement.executeUpdate("LOCK TABLES `stashes` WRITE");
                    try {
                        try (PreparedStatement stmt = connection.prepareStatement("SELECT `item` FROM `stashes` WHERE `uuid` = ? LIMIT 100")) {
                            stmt.setString(1, player.getUniqueId().toString());
                            try (ResultSet rs = stmt.executeQuery()) {
                                while (rs.next()) {
                                    Blob blob = rs.getBlob("item");
                                    byte[] bytes = blob.getBytes(1, (int) blob.length());
                                    items.add(ItemStack.deserializeBytes(bytes));
                                    byteList.add(bytes);
                                }
                            }
                        }
                        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM `stashes` WHERE `uuid` = ? AND `item` = ? LIMIT 1")) {
                            for (byte[] bytes : byteList) {
                                stmt.setString(1, player.getUniqueId().toString());
                                stmt.setBlob(2, new MariaDbBlob(bytes));
                                stmt.executeUpdate();
                            }
                        }
                    } finally {
                        statement.executeUpdate("UNLOCK TABLES");
                    }
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return items;
        }, async).thenApplyAsync(items -> {
            if (items.isEmpty()) {
                return true;
            }
            getLogger().info("Attempting to give " + items.size() + " item stacks to " + player.getName() + " (" + player.getUniqueId() + "):");
            ItemUtil.log(getLogger(), items);
            Collection<ItemStack> notFit = player.getInventory().addItem(items.toArray(new ItemStack[0])).values();
            getLogger().info("Re-adding " + notFit.size() + " item stacks to " + player.getName() + " (" + player.getUniqueId() + ")'s stash:");
            ItemUtil.log(getLogger(), notFit);
            notFit.forEach((itemStack) -> addItemToStash(player.getUniqueId(), itemStack));
            return notFit.isEmpty();
        }, sync);
    }
}
