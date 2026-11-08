package net.azisaba.itemstash

import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.CompletableFuture

interface ItemStash {
    /**
     * Add item to stash. This method blocks until the operation is complete. This method sets the expiration date to
     * `current time + 1 week`.
     * @param player player's uuid
     * @param itemStack item to add
     * @see addItemToStash
     */
    fun addItemToStash(player: UUID, itemStack: ItemStack) {
        addItemToStash(player, itemStack, System.currentTimeMillis() + 1000L * 60L * 60L * 24L * 7L)
    }

    /**
     * Add item to stash. This method blocks until the operation is complete.
     * @param player player's uuid
     * @param itemStack item to add
     * @param expiresAt expiration time/date. set to -1 to never expire
     */
    fun addItemToStash(player: UUID, itemStack: ItemStack, expiresAt: Long)

    /**
     * Get stash item count. This method blocks until the operation is complete.
     * @param player player's uuid
     * @return stash item count
     */
    fun getStashItemCount(player: UUID): Int

    fun getNearestExpirationTime(player: UUID): Long

    /**
     * Attempt to remove item from stash and give it to player.
     *
     * @param player the player
     * @return future that completes with true if all items were removed, false if there are still items in stash
     */
    fun dumpStash(player: Player): CompletableFuture<Boolean>

    companion object {
        @JvmStatic
        fun getInstance(): ItemStash = JavaPlugin.getPlugin(ItemStashPlugin::class.java)
    }
}
