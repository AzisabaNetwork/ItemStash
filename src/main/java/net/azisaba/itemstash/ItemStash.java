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
     * Add item to stash. This method blocks until the operation is complete. This method sets the expiration date to
     * <code>current time + 1 week</code>.
     * @param player player's uuid
     * @param itemStack item to add
     * @see #addItemToStash(UUID, ItemStack, long)
     */
    default void addItemToStash(@NotNull UUID player, @NotNull ItemStack itemStack) {
        addItemToStash(player, itemStack, System.currentTimeMillis() + 1000L * 60L * 60L * 24L * 7L);
    }

    /**
     * Add item to stash. This method blocks until the operation is complete.
     * @param player player's uuid
     * @param itemStack item to add
     * @param expiresAt expiration time/date. set to -1 to never expire
     */
    void addItemToStash(@NotNull UUID player, @NotNull ItemStack itemStack, long expiresAt);

    /**
     * Get stash item count. This method blocks until the operation is complete.
     * @param player player's uuid
     * @return stash item count
     */
    int getStashItemCount(@NotNull UUID player);

    long getNearestExpirationTime(@NotNull UUID player);

    /**
     * Attempt to remove item from stash and give it to player. This method blocks until the operation is complete.
     *
     * @param player player's uuid
     * @return true if all items were removed, false if there are still items in stash
     */
    CompletableFuture<Boolean> dumpStash(@NotNull Player player);
}
