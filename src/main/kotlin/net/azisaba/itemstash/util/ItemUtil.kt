package net.azisaba.itemstash.util

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.logging.Logger

object ItemUtil {
    fun log(logger: Logger, items: Collection<ItemStack>) {
        items.forEach { log(logger, it) }
    }

    fun log(logger: Logger, item: ItemStack) {
        try {
            logger.info("  " + com.github.mori01231.lifecore.util.ItemUtil.toString(item))
        } catch (_: NoClassDefFoundError) {
            logger.info("  $item")
        }
    }

    fun firstPartial(inv: Inventory, item: ItemStack?): Int {
        if (item == null) return -1
        return inv.storageContents.indexOfFirst { it != null && it.amount < it.maxStackSize && it.isSimilar(item) }
    }

    fun addItem(inventory: Inventory, vararg items: ItemStack): HashMap<Int, ItemStack> {
        val leftover = HashMap<Int, ItemStack>()

        outer@ for ((i, item) in items.withIndex()) {
            // items stored on 1.15.2 need their MythicMobs ID restored before they can stack with / act as current items
            LegacyItemRepair.repair(item)
            if (StorageBoxUtil.tryAddItemToStorageBox(inventory, item)) {
                continue
            }

            while (true) {
                val firstPartial = firstPartial(inventory, item)
                if (firstPartial == -1) {
                    val firstFree = inventory.firstEmpty()
                    if (firstFree == -1) {
                        leftover[i] = item
                        continue@outer
                    }

                    if (item.amount <= item.maxStackSize) {
                        inventory.setItem(firstFree, item)
                        continue@outer
                    }

                    val originalAmount = item.amount
                    item.amount = item.maxStackSize
                    inventory.setItem(firstFree, item)
                    item.amount = originalAmount - item.maxStackSize
                } else {
                    val partialItem = inventory.getItem(firstPartial) ?: continue
                    val amount = item.amount
                    val partialAmount = partialItem.amount
                    val maxAmount = partialItem.maxStackSize
                    if (amount + partialAmount <= maxAmount) {
                        partialItem.amount = amount + partialAmount
                        inventory.setItem(firstPartial, partialItem)
                        continue@outer
                    }

                    partialItem.amount = maxAmount
                    inventory.setItem(firstPartial, partialItem)
                    item.amount = amount + partialAmount - maxAmount
                }
            }
        }

        return leftover
    }
}
