package net.azisaba.itemstash;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

    /**
     * Get stash item count. This method blocks until the operation is complete.
     * @param player player's uuid
     * @return stash item count
     */
    int getStashItemCount(@NotNull UUID player);

    /**
     * Attempt to remove item from stash and give it to player. This method blocks until the operation is complete.
     *
     * @param player player's uuid
     * @return true if all items were removed, false if there are still items in stash
     */
    CompletableFuture<Boolean> dumpStash(@NotNull Player player);
}
