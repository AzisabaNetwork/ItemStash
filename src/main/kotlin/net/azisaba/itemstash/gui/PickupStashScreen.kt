package net.azisaba.itemstash.gui

import net.azisaba.itemstash.util.ItemCodec
import net.azisaba.itemstash.ItemStashPlugin
import net.azisaba.itemstash.command.PickupStashCommand
import net.azisaba.itemstash.sql.DBConnector
import net.azisaba.itemstash.sql.getBlobBytes
import net.azisaba.itemstash.util.ItemUtil
import net.azisaba.itemstash.util.formatDate
import net.azisaba.itemstash.util.noItalic
import net.azisaba.itemstash.util.plus
import net.azisaba.itemstash.util.text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.mariadb.jdbc.MariaDbBlob
import java.sql.SQLException
import java.util.AbstractMap

class PickupStashScreen(items: List<Map.Entry<ItemStack, Long>>) : InventoryHolder {
    private val inventory: Inventory = Bukkit.createInventory(this, 54, Component.text("Stash回収"))
    private val items: List<Map.Entry<ItemStack, Long>> = flatten(items)
    private var page = 0
    private var acceptingClick = true

    init {
        initInventory()
    }

    fun initInventory() {
        inventory.clear()
        val count = minOf(PAGE_SIZE, items.size - page * PAGE_SIZE)
        for (i in 0 until count) {
            val (item, expiresAt) = items[page * PAGE_SIZE + i]
            val screenItem = item.clone()
            if (screenItem.type.isAir) {
                JavaPlugin.getPlugin(ItemStashPlugin::class.java).getSLF4JLogger()
                    .info("{} (#{}) does not have item meta", screenItem, i)
                continue
            }
            val lore = screenItem.lore()?.toMutableList()?.apply { add(Component.empty()) } ?: mutableListOf()
            lore.add(
                (text("Stashに格納されているアイテム数: ", NamedTextColor.GOLD, TextDecoration.BOLD) +
                    text(screenItem.amount, NamedTextColor.GREEN, TextDecoration.BOLD)).noItalic()
            )
            if (expiresAt > 0) {
                lore.add((text("直近の有効期限: ", NamedTextColor.GOLD) + text(formatDate(expiresAt), NamedTextColor.RED)).noItalic())
            }
            screenItem.lore(lore)
            inventory.setItem(i, screenItem)
        }
        inventory.setItem(SLOT_PREVIOUS, guiItem(Material.ARROW, text("前のページ", NamedTextColor.GOLD)))
        inventory.setItem(
            SLOT_DELETE_ALL,
            guiItem(
                Material.BARRIER,
                text("すべて削除", NamedTextColor.RED, TextDecoration.BOLD),
                text("スタッシュ内のアイテムをすべて削除します。", NamedTextColor.GRAY),
            )
        )
        inventory.setItem(SLOT_NEXT, guiItem(Material.ARROW, text("次のページ", NamedTextColor.GOLD)))
    }

    override fun getInventory(): Inventory = inventory

    class EventListener(private val plugin: ItemStashPlugin) : Listener {
        @EventHandler
        fun onInventoryDrag(e: InventoryDragEvent) {
            if (e.inventory.holder is PickupStashScreen) {
                e.isCancelled = true
            }
        }

        @EventHandler
        fun onInventoryClick(e: InventoryClickEvent) {
            val screen = e.inventory.holder as? PickupStashScreen ?: return
            e.isCancelled = true
            if (e.clickedInventory?.holder !is PickupStashScreen) return
            if (!screen.acceptingClick) return
            when (e.slot) {
                SLOT_PREVIOUS -> {
                    if (screen.page > 0) {
                        screen.page--
                        screen.initInventory()
                    }
                    return
                }

                SLOT_DELETE_ALL -> {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        e.whoClicked.openInventory(DeleteConfirmScreen(screen.items).inventory)
                    })
                    return
                }

                SLOT_NEXT -> {
                    if (screen.page < screen.items.size / PAGE_SIZE) {
                        screen.page++
                        screen.initInventory()
                    }
                    return
                }
            }
            val current = e.currentItem
            if (e.slot >= PAGE_SIZE || current == null || current.type == Material.AIR) return

            val player = e.whoClicked as Player
            val uuid = player.uniqueId
            PickupStashCommand.PROCESSING.add(uuid)
            val stack = screen.items[screen.page * PAGE_SIZE + e.slot].key
            val originalAmount = stack.amount
            screen.acceptingClick = false
            Bukkit.getScheduler().runTask(plugin, Runnable {
                player.closeInventory()
                player.sendMessage(text("処理中です...", NamedTextColor.GRAY))
                plugin.logger.info("Attempting to take $stack from stash of ${player.name}")
                val start = System.currentTimeMillis()
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    try {
                        DBConnector.use { connection ->
                            DBConnector.setOperationInProgress(uuid, true)
                            val toRemove = mutableListOf<ByteArray>()
                            connection.prepareStatement("SELECT `item` FROM `stashes` WHERE `uuid` = ?").use { stmt ->
                                stmt.setString(1, uuid.toString())
                                stmt.executeQuery().use { rs ->
                                    while (rs.next()) {
                                        val bytes = rs.getBlobBytes("item")
                                        if (ItemCodec.decode(bytes)?.isSimilar(stack) == true && toRemove.none { it.contentEquals(bytes) }) {
                                            toRemove.add(bytes)
                                        }
                                    }
                                }
                            }
                            plugin.logger.info("Collected ${toRemove.size} items to remove")
                            connection.prepareStatement("DELETE FROM `stashes` WHERE `uuid` = ? AND `item` = ?").use { stmt ->
                                for (bytes in toRemove) {
                                    stmt.setString(1, uuid.toString())
                                    stmt.setBlob(2, MariaDbBlob(bytes))
                                    stmt.executeUpdate()
                                }
                            }
                        }
                    } catch (ex: SQLException) {
                        PickupStashCommand.PROCESSING.remove(uuid)
                        try {
                            DBConnector.setOperationInProgress(uuid, false)
                        } catch (exc: SQLException) {
                            exc.addSuppressed(ex)
                            throw exc
                        }
                        throw ex
                    }
                    Bukkit.getScheduler().runTask(plugin, Runnable {
                        val notFit: Collection<ItemStack> =
                            if (player.isOnline) ItemUtil.addItem(player.inventory, stack).values else listOf(stack)
                        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                            try {
                                val elapsed = text(" [${System.currentTimeMillis() - start}ms]", NamedTextColor.DARK_GRAY)
                                if (notFit.isEmpty()) {
                                    player.sendMessage(text("すべてのアイテム(${originalAmount}個)を受け取りました。", NamedTextColor.GREEN) + elapsed)
                                } else {
                                    var amount = 0
                                    for (item in notFit) {
                                        plugin.addItemToStash(uuid, item)
                                        amount += item.amount
                                    }
                                    player.sendMessage(text(amount, NamedTextColor.GOLD) + text("個のアイテムが受け取れませんでした。", NamedTextColor.RED) + elapsed)
                                }
                            } finally {
                                PickupStashCommand.PROCESSING.remove(uuid)
                                try {
                                    DBConnector.setOperationInProgress(uuid, false)
                                } catch (ex: SQLException) {
                                    plugin.getSLF4JLogger().error("Failed to set operation_in_progress state", ex)
                                }
                            }
                        })
                    })
                })
            })
        }
    }

    companion object {
        private const val PAGE_SIZE = 45
        private const val SLOT_PREVIOUS = 45
        private const val SLOT_DELETE_ALL = 49
        private const val SLOT_NEXT = 53

        /** Merges similar items, summing their amounts and keeping the nearest expiration time. */
        private fun flatten(items: List<Map.Entry<ItemStack, Long>>): List<Map.Entry<ItemStack, Long>> {
            val flattened = mutableListOf<MutableMap.MutableEntry<ItemStack, Long>>()
            for ((item, expiresAt) in items) {
                val existing = flattened.find { it.key.isSimilar(item) }
                if (existing != null) {
                    existing.key.amount += item.amount
                    if (existing.value > expiresAt) {
                        existing.setValue(expiresAt)
                    }
                } else {
                    flattened.add(AbstractMap.SimpleEntry(item, expiresAt))
                }
            }
            return flattened
        }
    }
}
