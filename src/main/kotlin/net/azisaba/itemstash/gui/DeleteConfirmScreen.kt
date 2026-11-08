package net.azisaba.itemstash.gui

import net.azisaba.itemstash.ItemStashPlugin
import net.azisaba.itemstash.command.PickupStashCommand
import net.azisaba.itemstash.sql.DBConnector
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
import java.sql.SQLException

class DeleteConfirmScreen(private val items: List<Map.Entry<ItemStack, Long>>) : InventoryHolder {
    private val inventory: Inventory = Bukkit.createInventory(this, 9, Component.text("本当に全削除しますか？"))
    private var acceptingClick = true

    init {
        initInventory()
    }

    private fun initInventory() {
        inventory.clear()

        inventory.setItem(
            SLOT_DELETE,
            guiItem(
                Material.RED_STAINED_GLASS_PANE,
                text("全削除する", NamedTextColor.RED, TextDecoration.BOLD),
                text("スタッシュ内の全アイテムを削除します。", NamedTextColor.GRAY),
            )
        )

        val filler = guiItem(Material.GRAY_STAINED_GLASS_PANE, Component.empty())
        for (i in 1..3) inventory.setItem(i, filler.clone())
        for (i in 5..7) inventory.setItem(i, filler.clone())

        inventory.setItem(
            4,
            guiItem(
                Material.BARRIER,
                text("Stashを全削除", NamedTextColor.RED, TextDecoration.BOLD),
                text("スタッシュ内の全アイテム(", NamedTextColor.GRAY) + text("${items.size}種類", NamedTextColor.YELLOW) +
                    text(")を", NamedTextColor.GRAY),
                text("削除します。この操作は元に戻せません。", NamedTextColor.GRAY),
            )
        )

        inventory.setItem(
            SLOT_BACK,
            guiItem(
                Material.LIME_STAINED_GLASS_PANE,
                text("戻る", NamedTextColor.GREEN, TextDecoration.BOLD),
                text("元の画面に戻ります。", NamedTextColor.GRAY),
            )
        )
    }

    override fun getInventory(): Inventory = inventory

    class EventListener(private val plugin: ItemStashPlugin) : Listener {
        @EventHandler
        fun onInventoryDrag(e: InventoryDragEvent) {
            if (e.inventory.holder is DeleteConfirmScreen) {
                e.isCancelled = true
            }
        }

        @EventHandler
        fun onInventoryClick(e: InventoryClickEvent) {
            val screen = e.inventory.holder as? DeleteConfirmScreen ?: return
            e.isCancelled = true
            if (e.clickedInventory?.holder !is DeleteConfirmScreen) return
            if (!screen.acceptingClick) return

            if (e.slot == SLOT_BACK) {
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    e.whoClicked.openInventory(PickupStashScreen(screen.items).inventory)
                })
                return
            }

            if (e.slot == SLOT_DELETE) {
                screen.acceptingClick = false
                val player = e.whoClicked as Player
                PickupStashCommand.PROCESSING.add(player.uniqueId)
                Bukkit.getScheduler().runTask(plugin, Runnable {
                    player.closeInventory()
                    player.sendMessage(text("処理中です...", NamedTextColor.GRAY))
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                        try {
                            DBConnector.setOperationInProgress(player.uniqueId, true)
                            val deleted = DBConnector.getPrepareStatement("DELETE FROM `stashes` WHERE `uuid` = ?") { stmt ->
                                stmt.setString(1, player.uniqueId.toString())
                                stmt.executeUpdate()
                            }
                            Bukkit.getScheduler().runTask(plugin, Runnable {
                                player.sendMessage(
                                    text("スタッシュ内のすべてのアイテムを削除しました。", NamedTextColor.GREEN) +
                                        text(" (${deleted}件)", NamedTextColor.DARK_GRAY)
                                )
                            })
                        } catch (ex: SQLException) {
                            Bukkit.getScheduler().runTask(plugin, Runnable {
                                player.sendMessage(text("削除中にエラーが発生しました。", NamedTextColor.RED))
                            })
                            throw ex
                        } finally {
                            PickupStashCommand.PROCESSING.remove(player.uniqueId)
                            try {
                                DBConnector.setOperationInProgress(player.uniqueId, false)
                            } catch (ex: SQLException) {
                                plugin.getSLF4JLogger().error("Failed to set operation_in_progress state", ex)
                            }
                        }
                    })
                })
            }
        }
    }

    companion object {
        private const val SLOT_DELETE = 0
        private const val SLOT_BACK = 8
    }
}

/** Creates a GUI item with a non-italic display name and lore. */
internal fun guiItem(material: Material, name: Component, vararg lore: Component): ItemStack =
    ItemStack(material).apply {
        editMeta { meta ->
            meta.displayName(name.noItalic())
            if (lore.isNotEmpty()) {
                meta.lore(lore.map { it.noItalic() })
            }
        }
    }
