package net.azisaba.itemstash.util

import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method
import java.util.logging.Level

/**
 * Repairs items that were stored on 1.15.2 (MythicMobs 4) before they are handed to a player.
 *
 * Paper's DataFixer upgrades the vanilla data of those items, but the MythicMobs 4 `MYTHIC_TYPE` tag only ends up in
 * `minecraft:custom_data`, so MythicMobs 5 no longer recognizes them. MythicItemIdCompat converts such items in
 * inventories through events, which do not fire when ItemStash puts items into an inventory directly; it exposes
 * `preserveMythicId(ItemStack)` for that case, which is called here through reflection (no compile-time dependency).
 *
 * Must be called on the main thread.
 */
object LegacyItemRepair {
    private const val MYTHIC_COMPAT = "MythicItemIdCompat"

    private var cachedPlugin: Plugin? = null
    private var cachedMethod: Method? = null
    private var errors = 0

    private fun preserveMethod(): Pair<Plugin, Method>? {
        val plugin = Bukkit.getPluginManager().getPlugin(MYTHIC_COMPAT)?.takeIf { it.isEnabled } ?: return null
        if (plugin !== cachedPlugin) {
            cachedPlugin = plugin
            cachedMethod = try {
                plugin.javaClass.getMethod("preserveMythicId", ItemStack::class.java)
            } catch (e: ReflectiveOperationException) {
                Bukkit.getLogger().log(Level.WARNING, "[ItemStash] $MYTHIC_COMPAT does not provide preserveMythicId", e)
                null
            }
        }
        return cachedMethod?.let { plugin to it }
    }

    /** Repairs the item in place. */
    fun repair(item: ItemStack) {
        if (item.type.isAir) return
        val (plugin, method) = preserveMethod() ?: return
        try {
            method.invoke(plugin, item)
        } catch (e: ReflectiveOperationException) {
            if (++errors <= 10) Bukkit.getLogger().log(Level.WARNING, "[ItemStash] Could not repair legacy item $item", e)
        } catch (e: RuntimeException) {
            if (++errors <= 10) Bukkit.getLogger().log(Level.WARNING, "[ItemStash] Could not repair legacy item $item", e)
        }
    }
}
