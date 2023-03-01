package net.azisaba.itemstash.util;

import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
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
}
