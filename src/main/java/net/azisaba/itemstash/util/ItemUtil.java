package net.azisaba.itemstash.util;

import org.apache.commons.lang.Validate;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.logging.Logger;

public class ItemUtil {
    public static void log(@NotNull Logger logger, @NotNull Collection<ItemStack> items) {
        for (ItemStack item : items) {
            log(logger, item);
        }
    }

    public static void log(@NotNull Logger logger, @NotNull ItemStack item) {
        try {
            logger.info("  " + com.github.mori01231.lifecore.util.ItemUtil.toString(item));
        } catch (NoClassDefFoundError e) {
            logger.info("  " + item);
        }
    }

    public static int firstPartial(@NotNull Inventory inv, ItemStack item) {
        ItemStack[] inventory = inv.getStorageContents();
        if (item != null) {
            for (int i = 0; i < inventory.length; ++i) {
                ItemStack cItem = inventory[i];
                if (cItem != null && cItem.getAmount() < cItem.getMaxStackSize() && cItem.isSimilar(item)) {
                    return i;
                }
            }
        }
        return -1;
    }

    public static @NotNull HashMap<Integer, ItemStack> addItem(@NotNull Inventory inventory, ItemStack... items) {
        Validate.noNullElements(items, "Item cannot be null");
        HashMap<Integer, ItemStack> leftover = new HashMap<>();

        label:
        for (int i = 0; i < items.length; ++i) {
            ItemStack item = items[i];

            while (true) {
                int firstPartial = firstPartial(inventory, item);
                if (firstPartial == -1) {
                    int firstFree = inventory.firstEmpty();
                    if (firstFree == -1) {
                        leftover.put(i, item);
                        continue label;
                    }

                    if (item.getAmount() <= item.getMaxStackSize()) {
                        inventory.setItem(firstFree, item);
                        continue label;
                    }

                    int originalAmount = item.getAmount();
                    item.setAmount(item.getMaxStackSize());
                    inventory.setItem(firstFree, item);
                    item.setAmount(originalAmount - item.getMaxStackSize());
                } else {
                    ItemStack partialItem = inventory.getItem(firstPartial);
                    if (partialItem == null) {
                        continue;
                    }
                    int amount = item.getAmount();
                    int partialAmount = partialItem.getAmount();
                    int maxAmount = partialItem.getMaxStackSize();
                    if (amount + partialAmount <= maxAmount) {
                        partialItem.setAmount(amount + partialAmount);
                        inventory.setItem(firstPartial, partialItem);
                        continue label;
                    }

                    partialItem.setAmount(maxAmount);
                    inventory.setItem(firstPartial, partialItem);
                    item.setAmount(amount + partialAmount - maxAmount);
                }
            }
        }

        return leftover;
    }
}
