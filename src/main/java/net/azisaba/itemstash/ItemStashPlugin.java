package net.azisaba.itemstash;

import net.azisaba.itemstash.command.ItemStashCommand;
import net.azisaba.itemstash.command.PickupStashCommand;
import net.azisaba.itemstash.command.StashNotifyCommand;
import net.azisaba.itemstash.gui.PickupStashScreen;
import net.azisaba.itemstash.listener.JoinListener;
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
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class ItemStashPlugin extends JavaPlugin implements ItemStash {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
    private final Executor sync = r -> Bukkit.getScheduler().runTask(this, r);
    private final Executor async = r -> Bukkit.getScheduler().runTaskAsynchronously(this, r);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        DatabaseConfig databaseConfig = new DatabaseConfig(Objects.requireNonNull(getConfig().getConfigurationSection("database"), "database"));
        try {
            int rows = DBConnector.init(databaseConfig);
            getLogger().info("Removed " + rows + " expired items");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        Bukkit.getPluginManager().registerEvents(new PickupStashScreen.EventListener(this), this);
        Bukkit.getPluginManager().registerEvents(new JoinListener(this), this);
        Objects.requireNonNull(Bukkit.getPluginCommand("pickupstash"))
                .setExecutor(new PickupStashCommand(this));
        Objects.requireNonNull(Bukkit.getPluginCommand("itemstash"))
                .setExecutor(new ItemStashCommand(this));
        Objects.requireNonNull(Bukkit.getPluginCommand("stashnotify"))
                .setExecutor(new StashNotifyCommand(this));
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                try {
                    if (DBConnector.isSuppressNotification(player.getUniqueId())) {
                        continue;
                    }
                } catch (SQLException e) {
                    getSLF4JLogger().warn("Failed to check for notification status", e);
                    continue;
                }
                forceNotifyStash(player);
            }
        }, 20 * 60 * 10, 20 * 60 * 10);
    }

    public void forceNotifyStash(Player player) {
        int count = getStashItemCount(player.getUniqueId());
        if (count == 0) {
            return;
        }
        long exp = getNearestExpirationTime(player.getUniqueId());
        player.sendMessage(ChatColor.GOLD + "Stashに" + ChatColor.RED + count + ChatColor.GOLD + "件のアイテムがあります！");
        player.sendMessage(ChatColor.GOLD + "受け取るには" + ChatColor.AQUA + "/pickupstash" + ChatColor.GOLD + "を実行してください。");
        if (exp > 0) {
            String expString = DATE_FORMAT.format(exp);
            player.sendMessage(ChatColor.GOLD + "直近の有効期限は" + ChatColor.RED + expString + ChatColor.GOLD + "です。");
        }
    }

    @Override
    public void onDisable() {
        DBConnector.close();
    }

    @Override
    public void addItemToStash(@NotNull UUID player, @NotNull ItemStack itemStack, long expiresAt) {
        try {
            getLogger().info("Adding item to stash of " + player + ":");
            ItemUtil.log(getLogger(), itemStack);
            DBConnector.runPrepareStatement("INSERT INTO `stashes` (`uuid`, `item`, `expires_at`, `true_amount`) VALUES (?, ?, ?, ?)", statement -> {
                statement.setString(1, player.toString());
                int amount = itemStack.getAmount();
                itemStack.setAmount(Math.min(64, itemStack.getAmount()));
                statement.setBlob(2, new MariaDbBlob(itemStack.serializeAsBytes()));
                itemStack.setAmount(amount);
                statement.setLong(3, expiresAt);
                statement.setInt(4, amount);
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
    public long getNearestExpirationTime(@NotNull UUID player) {
        try {
            return DBConnector.getPrepareStatement("SELECT `expires_at` FROM `stashes` WHERE `uuid` = ? AND `expires_at` != -1 ORDER BY `expires_at` LIMIT 1", statement -> {
                statement.setString(1, player.toString());
                try (ResultSet rs = statement.executeQuery()) {
                    if (rs.next()) {
                        return rs.getLong(1);
                    }
                    return 0L;
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
            try (Connection connection = DBConnector.getConnection()) {
                Statement statement = connection.createStatement();
                statement.executeUpdate("LOCK TABLES `stashes` WRITE");
                try {
                    try (PreparedStatement stmt = connection.prepareStatement("SELECT `item`, `true_amount` FROM `stashes` WHERE `uuid` = ? ORDER BY IF(`expires_at` = -1, 1, 0), `expires_at` LIMIT 100")) {
                        stmt.setString(1, player.getUniqueId().toString());
                        try (ResultSet rs = stmt.executeQuery()) {
                            while (rs.next()) {
                                int trueAmount = rs.getInt("true_amount");
                                Blob blob = rs.getBlob("item");
                                byte[] bytes = blob.getBytes(1, (int) blob.length());
                                ItemStack item = ItemStack.deserializeBytes(bytes);
                                if (trueAmount > 0) {
                                    item.setAmount(trueAmount);
                                }
                                items.add(item);
                                byteList.add(bytes);
                            }
                        }
                    }
                    try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM `stashes` WHERE `uuid` = ? AND `item` = ? ORDER BY IF(`expires_at` = -1, 1, 0), `expires_at` LIMIT 1")) {
                        for (byte[] bytes : byteList) {
                            stmt.setString(1, player.getUniqueId().toString());
                            stmt.setBlob(2, new MariaDbBlob(bytes));
                            stmt.executeUpdate();
                        }
                    }
                } finally {
                    statement.executeUpdate("UNLOCK TABLES");
                }
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
            return items;
        }, async).thenApplyAsync(items -> {
            if (items.isEmpty()) {
                return Collections.<ItemStack>emptyList();
            }
            getLogger().info("Attempting to give " + items.size() + " item stacks to " + player.getName() + " (" + player.getUniqueId() + "):");
            ItemUtil.log(getLogger(), items);
            return ItemUtil.addItem(player.getInventory(), items.toArray(new ItemStack[0])).values();
        }, sync).thenApplyAsync(notFit -> {
            getLogger().info("Re-adding " + notFit.size() + " item stacks to " + player.getName() + " (" + player.getUniqueId() + ")'s stash:");
            ItemUtil.log(getLogger(), notFit);
            notFit.forEach((itemStack) -> addItemToStash(player.getUniqueId(), itemStack));
            return notFit.isEmpty();
        }, async);
    }
}
