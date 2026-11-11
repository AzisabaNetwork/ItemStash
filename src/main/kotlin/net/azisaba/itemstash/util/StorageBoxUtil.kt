package net.azisaba.itemstash.util

import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import xyz.acrylicstyle.storageBox.utils.StorageBoxUtils
import java.util.logging.Level

object StorageBoxUtil {
    @Volatile
    private var warned = false

    /**
     * Try to store item to storage box.
     * Any failure inside StorageBox (e.g. stale classes after StorageBox was reloaded, or an API mismatch)
     * is swallowed so the caller can fall back to the normal inventory.
     * @param inventory the inventory to find storage box from
     * @param stack the item
     * @return true if the item is stored to storage box, false otherwise
     */
    fun tryAddItemToStorageBox(inventory: Inventory, stack: ItemStack): Boolean {
        if (!Bukkit.getPluginManager().isPluginEnabled("StorageBox")) return false
        return try {
            addItemToStorageBox(inventory, stack)
        } catch (e: Exception) {
            warnOnce(e)
            false
        } catch (e: LinkageError) {
            warnOnce(e)
            false
        }
    }

    // Kept in a separate method so StorageBox classes are only loaded when the plugin is present
    private fun addItemToStorageBox(inventory: Inventory, stack: ItemStack): Boolean {
        val entry = StorageBoxUtils.getStorageBoxForType(inventory, stack) ?: return false
        val storageBox = entry.value
        storageBox.amount += stack.amount
        // build the new stack before touching the inventory so a failure here leaves the inventory unchanged
        val newStack = storageBox.itemStack
        inventory.setItem(entry.key, newStack)
        return true
    }

    private fun warnOnce(e: Throwable) {
        if (warned) return
        warned = true
        Bukkit.getPluginManager().getPlugin("ItemStash")?.logger?.log(
            Level.WARNING,
            "Failed to store item into StorageBox; falling back to the normal inventory. " +
                    "If StorageBox was reloaded at runtime, restart the server. (further errors are suppressed)",
            e,
        )
    }
}
