package net.azisaba.itemstash.gui;

import net.azisaba.itemstash.ItemStashPlugin;
import net.azisaba.itemstash.command.PickupStashCommand;
import net.azisaba.itemstash.sql.DBConnector;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class DeleteConfirmScreen implements InventoryHolder {

    private static final int SLOT_DELETE = 0;
    private static final int SLOT_BACK = 8;

    private final Inventory inventory = Bukkit.createInventory(this, 9, "本当に全削除しますか？");
    private final List<Map.Entry<ItemStack, Long>> items;
    private boolean acceptingClick = true;

    public DeleteConfirmScreen(@NotNull List<Map.Entry<ItemStack, Long>> items) {
        this.items = items;
        initInventory();
    }

    private void initInventory() {
        inventory.clear();

        ItemStack deleteItem = new ItemStack(Material.RED_STAINED_GLASS_PANE);
        ItemMeta deleteMeta = deleteItem.getItemMeta();
        deleteMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "全削除する");
        deleteMeta.setLore(Collections.singletonList(ChatColor.GRAY + "スタッシュ内の全アイテムを削除します。"));
        deleteItem.setItemMeta(deleteMeta);
        inventory.setItem(SLOT_DELETE, deleteItem);

        ItemStack filler = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta fillerMeta = filler.getItemMeta();
        fillerMeta.setDisplayName(ChatColor.RESET + "");
        filler.setItemMeta(fillerMeta);
        for (int i = 1; i <= 3; i++) inventory.setItem(i, filler.clone());
        for (int i = 5; i <= 7; i++) inventory.setItem(i, filler.clone());

        ItemStack infoItem = new ItemStack(Material.BARRIER);
        ItemMeta infoMeta = infoItem.getItemMeta();
        infoMeta.setDisplayName(ChatColor.RED + "" + ChatColor.BOLD + "Stashを全削除");
        infoMeta.setLore(Arrays.asList(
                ChatColor.GRAY + "スタッシュ内の全アイテム(" + ChatColor.YELLOW + items.size() + "種類" + ChatColor.GRAY + ")を",
                ChatColor.GRAY + "削除します。この操作は元に戻せません。"
        ));
        infoItem.setItemMeta(infoMeta);
        inventory.setItem(4, infoItem);

        ItemStack backItem = new ItemStack(Material.LIME_STAINED_GLASS_PANE);
        ItemMeta backMeta = backItem.getItemMeta();
        backMeta.setDisplayName(ChatColor.GREEN + "" + ChatColor.BOLD + "戻る");
        backMeta.setLore(Collections.singletonList(ChatColor.GRAY + "元の画面に戻ります。"));
        backItem.setItemMeta(backMeta);
        inventory.setItem(SLOT_BACK, backItem);
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
            if (e.getInventory().getHolder() instanceof DeleteConfirmScreen) {
                e.setCancelled(true);
            }
        }

        @EventHandler
        public void onInventoryClick(InventoryClickEvent e) {
            if (!(e.getInventory().getHolder() instanceof DeleteConfirmScreen)) {
                return;
            }
            e.setCancelled(true);
            if (e.getClickedInventory() == null || !(e.getClickedInventory().getHolder() instanceof DeleteConfirmScreen)) {
                return;
            }
            DeleteConfirmScreen screen = (DeleteConfirmScreen) e.getInventory().getHolder();
            if (!screen.acceptingClick) return;

            if (e.getSlot() == SLOT_BACK) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    e.getWhoClicked().openInventory(new PickupStashScreen(screen.items).getInventory());
                });
                return;
            }

            if (e.getSlot() == SLOT_DELETE) {
                screen.acceptingClick = false;
                Player player = (Player) e.getWhoClicked();
                PickupStashCommand.PROCESSING.add(player.getUniqueId());
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.closeInventory();
                    player.sendMessage(ChatColor.GRAY + "処理中です...");
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        try (Connection connection = DBConnector.getConnection()) {
                            DBConnector.setOperationInProgress(player.getUniqueId(), true);
                            try (PreparedStatement stmt = connection.prepareStatement(
                                    "DELETE FROM `stashes` WHERE `uuid` = ?")) {
                                stmt.setString(1, player.getUniqueId().toString());
                                int deleted = stmt.executeUpdate();
                                Bukkit.getScheduler().runTask(plugin, () ->
                                        player.sendMessage(ChatColor.GREEN + "スタッシュ内のすべてのアイテムを削除しました。"
                                                + ChatColor.DARK_GRAY + " (" + deleted + "件)"));
                            }
                        } catch (SQLException ex) {
                            Bukkit.getScheduler().runTask(plugin, () ->
                                    player.sendMessage(ChatColor.RED + "削除中にエラーが発生しました。"));
                            throw new RuntimeException(ex);
                        } finally {
                            PickupStashCommand.PROCESSING.remove(player.getUniqueId());
                            try {
                                DBConnector.setOperationInProgress(player.getUniqueId(), false);
                            } catch (SQLException ex) {
                                plugin.getSLF4JLogger().error("Failed to set operation_in_progress state", ex);
                            }
                        }
                    });
                });
            }
        }
    }
}
