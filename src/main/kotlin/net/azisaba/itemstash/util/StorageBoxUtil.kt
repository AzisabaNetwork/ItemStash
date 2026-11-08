package net.azisaba.itemstash.util

import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import xyz.acrylicstyle.storageBox.utils.StorageBoxUtils

object StorageBoxUtil {
    /**
     * Try to store item to storage box.
     * @param inventory the inventory to find storage box from
     * @param stack the item
     * @return true if the item is stored to storage box, false otherwise
     */
    fun tryAddItemToStorageBox(inventory: Inventory, stack: ItemStack): Boolean {
        if (!Bukkit.getPluginManager().isPluginEnabled("StorageBox")) return false
        return addItemToStorageBox(inventory, stack)
    }

    // Kept in a separate method so StorageBox classes are only loaded when the plugin is present
    private fun addItemToStorageBox(inventory: Inventory, stack: ItemStack): Boolean {
        val entry = StorageBoxUtils.getStorageBoxForType(inventory, stack) ?: return false
        val storageBox = entry.value
        storageBox.amount += stack.amount
        inventory.setItem(entry.key, storageBox.itemStack)
        return true
    }
}
