package net.azisaba.itemstash;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public interface ItemStash {
    static @NotNull ItemStash getInstance() {
        return JavaPlugin.getPlugin(ItemStashPlugin.class);
    }

    /**
     * Add item to stash. This method blocks until the operation is complete.
     * @param player player's uuid
     * @param itemStack item to add
     */
    void addItemToStash(@NotNull UUID player, @NotNull ItemStack itemStack);

    int getStashItemCount(@NotNull UUID player);

    /**
     * Attempt to remove item from stash and give it to player. This method blocks until the operation is complete.
     * @param player player's uuid
     * @return true if all items were removed, false if there are still items in stash
     */
    boolean dumpStash(@NotNull Player player);
}
