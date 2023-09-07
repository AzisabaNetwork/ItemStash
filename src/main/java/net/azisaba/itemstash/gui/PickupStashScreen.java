package net.azisaba.itemstash.gui;

import net.azisaba.itemstash.ItemStashPlugin;
import net.azisaba.itemstash.sql.DBConnector;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.mariadb.jdbc.MariaDbBlob;

import java.sql.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class PickupStashScreen implements InventoryHolder {
    private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
    private final Inventory inventory = Bukkit.createInventory(this, 54, "Stash回収");
    private final List<Map.Entry<ItemStack, Long>> items;
    private boolean acceptingClick = true;

    public PickupStashScreen(@NotNull List<Map.Entry<@NotNull ItemStack, @NotNull Long>> items) {
        this.items = new ArrayList<>(flatten(items).entrySet());
        initInventory();
    }

    private static @NotNull Map<@NotNull ItemStack, @NotNull Long> flatten(@NotNull List<Map.Entry<@NotNull ItemStack, @NotNull Long>> items) {
        Map<ItemStack, Long> flattened = new HashMap<>();
        for (Map.Entry<ItemStack, Long> item : items) {
            Optional<Map.Entry<ItemStack, Long>> opt = flattened.entrySet().stream().filter(is -> is.getKey().isSimilar(item.getKey())).findAny();
            if (opt.isPresent()) {
                opt.get().getKey().setAmount(opt.get().getKey().getAmount() + item.getKey().getAmount());
                if (opt.get().getValue() > item.getValue()) {
                    opt.get().setValue(item.getValue());
                }
            } else {
                flattened.put(item.getKey(), item.getValue());
            }
        }
        return flattened;
    }

    public void initInventory() {
        for (int i = 0; i < items.subList(0, Math.min(53, items.size())).size(); i++) {
            ItemStack screenItem = items.get(i).getKey().clone();
            long expiresAt = items.get(i).getValue();
            List<String> lore = screenItem.getLore();
            if (lore == null) {
                lore = new ArrayList<>();
            } else {
                lore.add("");
            }
            lore.add(ChatColor.GOLD.toString() + ChatColor.BOLD + "Stashに格納されているアイテム数: " + ChatColor.GREEN + ChatColor.BOLD + screenItem.getAmount());
            if (expiresAt > 0) {
                lore.add(ChatColor.GOLD + "直近の有効期限: " + ChatColor.RED + DATE_FORMAT.format(expiresAt));
            }
            screenItem.setLore(lore);
            inventory.setItem(i, screenItem);
        }
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public static class EventListener implements Listener {
        private final ItemStashPlugin plugin;

        public EventListener(@NotNull ItemStashPlugin plugin) {
            this.plugin = plugin;
        }

        @EventHandler
        public void onInventoryDrag(InventoryDragEvent e) {
            if (e.getInventory().getHolder() instanceof PickupStashScreen) {
                e.setCancelled(true);
            }
        }

        @EventHandler
        public void onInventoryClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof PickupStashScreen)) {
                return;
            }
            e.setCancelled(true);
            if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof PickupStashScreen)) {
                return;
            }
            PickupStashScreen screen = (PickupStashScreen) e.getInventory().getHolder();
            if (!screen.acceptingClick) return;
            if (screen.items.size() <= e.getSlot()) {
                return;
            }
            ItemStack stack = screen.items.get(e.getSlot()).getKey();
            int originalAmount = stack.getAmount();
            screen.acceptingClick = false;
            Bukkit.getScheduler().runTask(plugin, () -> {
                e.getWhoClicked().closeInventory();
                e.getWhoClicked().sendMessage(ChatColor.GRAY + "処理中です...");
                plugin.getLogger().info("Attempting to take " + stack + " from stash of " + e.getWhoClicked().getName());
                long start = System.currentTimeMillis();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    try (Connection connection = DBConnector.getConnection()) {
                        List<byte[]> toRemove = new ArrayList<>();
                        try (PreparedStatement stmt = connection.prepareStatement("SELECT `item` FROM `stashes` WHERE `uuid` = ? ORDER BY IF(`expires_at` = -1, 1, 0), `expires_at`")) {
                            stmt.setString(1, e.getWhoClicked().getUniqueId().toString());
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
                                stmt.setString(1, e.getWhoClicked().getUniqueId().toString());
                                stmt.setBlob(2, new MariaDbBlob(bytes));
                                stmt.executeUpdate();
                            }
                        }
                    } catch (SQLException ex) {
                        throw new RuntimeException(ex);
                    }
                    Collection<ItemStack> items = e.getWhoClicked().getInventory().addItem(stack).values();
                    if (items.isEmpty()) {
                        long elapsed = System.currentTimeMillis() - start;
                        e.getWhoClicked().sendMessage(ChatColor.GREEN + "すべてのアイテム(" + originalAmount + "個)を受け取りました。" + ChatColor.DARK_GRAY + " [" + elapsed + "ms]");
                    } else {
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                            int amount = 0;
                            for (ItemStack item : items) {
                                amount += item.getAmount();
                                int mod = item.getAmount() % 64;
                                int loopCount = item.getAmount() / 64;
                                for (int i = 0; i < loopCount; i++) {
                                    item.setAmount(64);
                                    plugin.addItemToStash(e.getWhoClicked().getUniqueId(), item);
                                }
                                if (mod > 0) {
                                    item.setAmount(mod);
                                    plugin.addItemToStash(e.getWhoClicked().getUniqueId(), item);
                                }
                            }
                            long elapsed = System.currentTimeMillis() - start;
                            e.getWhoClicked().sendMessage(ChatColor.GOLD.toString() + amount + ChatColor.RED + "個のアイテムが受け取れませんでした。" + ChatColor.DARK_GRAY + " [" + elapsed + "ms]");
                        });
                    }
                });
            });
        }
    }
}
