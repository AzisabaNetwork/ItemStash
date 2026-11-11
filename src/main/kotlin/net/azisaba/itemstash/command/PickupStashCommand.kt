package net.azisaba.itemstash.command

import net.azisaba.itemstash.util.ItemCodec
import net.azisaba.itemstash.ItemStashPlugin
import net.azisaba.itemstash.gui.PickupStashScreen
import net.azisaba.itemstash.sql.DBConnector
import net.azisaba.itemstash.sql.getBlobBytes
import net.azisaba.itemstash.util.plus
import net.azisaba.itemstash.util.text
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.sql.SQLException
import java.util.AbstractMap
import java.util.Collections
import java.util.UUID

class PickupStashCommand(private val plugin: ItemStashPlugin) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("このコマンドはコンソールから実行できません。")
            return true
        }

        if (args.size == 1 && args[0].equals("nogui", ignoreCase = true)) {
            pickupWithoutGui(sender)
            return true
        }

        val targetUUID = if (sender.hasPermission("itemstash.others") && args.isNotEmpty()) {
            UUID.fromString(args[0])
        } else {
            sender.uniqueId
        }
        if (!PROCESSING.add(targetUUID)) {
            sender.sendMessage(text("前回の処理が継続中です。しばらくしてからお試しください。(Local)", NamedTextColor.RED))
            return true
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                DBConnector.use { connection ->
                    if (DBConnector.isOperationInProgress(targetUUID)) {
                        sendMessage(sender, text("前回の処理が継続中です。しばらくしてからお試しください。(Server)", NamedTextColor.RED))
                        return@Runnable
                    }
                    sendMessage(sender, text("処理中です...", NamedTextColor.GRAY))
                    val items = mutableListOf<Map.Entry<ItemStack, Long>>()
                    connection.prepareStatement("SELECT `item`, `expires_at`, `true_amount` FROM `stashes` WHERE `uuid` = ?").use { stmt ->
                        stmt.setString(1, targetUUID.toString())
                        stmt.executeQuery().use { rs ->
                            while (rs.next()) {
                                val bytes = rs.getBlobBytes("item")
                                val expiresAt = rs.getLong("expires_at")
                                val trueAmount = rs.getInt("true_amount")
                                val item = ItemCodec.decode(bytes) ?: continue
                                if (trueAmount > 0) {
                                    item.amount = trueAmount
                                }
                                items.add(AbstractMap.SimpleImmutableEntry(item, expiresAt))
                            }
                        }
                    }
                    Bukkit.getScheduler().runTask(plugin, Runnable { sender.openInventory(PickupStashScreen(items).inventory) })
                }
            } finally {
                PROCESSING.remove(targetUUID)
            }
        })
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1 && sender is Player) {
            return listOf("nogui")
        }
        return emptyList()
    }

    private fun pickupWithoutGui(player: Player) {
        val uuid = player.uniqueId
        if (!PROCESSING.add(uuid)) {
            player.sendMessage(text("前回の処理が継続中です。しばらくしてからお試しください。(Local)", NamedTextColor.RED))
            return
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                if (DBConnector.isOperationInProgress(uuid)) {
                    sendMessage(player, text("前回の処理が継続中です。しばらくしてからお試しください。(Server)", NamedTextColor.RED))
                    PROCESSING.remove(uuid)
                    return@Runnable
                }
                DBConnector.setOperationInProgress(uuid, true)
            } catch (e: SQLException) {
                PROCESSING.remove(uuid)
                throw e
            }

            sendMessage(player, text("処理中です...", NamedTextColor.GRAY))
            val start = System.currentTimeMillis()
            plugin.dumpStash(player).whenComplete { result, throwable ->
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    try {
                        if (throwable != null) {
                            plugin.getSLF4JLogger().error("Failed to dump stash for {}", player.uniqueId, throwable)
                            sendMessage(player, text("アイテムの受け取り中にエラーが発生しました。", NamedTextColor.RED))
                            return@Runnable
                        }

                        val total = System.currentTimeMillis() - start
                        val elapsed = text(" [${total}ms]", NamedTextColor.DARK_GRAY)
                        if (result) {
                            sendMessage(player, text("アイテムをすべて受け取りました。", NamedTextColor.GREEN) + elapsed)
                        } else {
                            sendMessage(player, text("一部のアイテムを受け取れませんでした。", NamedTextColor.RED) + elapsed)
                        }
                    } finally {
                        PROCESSING.remove(uuid)
                        try {
                            DBConnector.setOperationInProgress(uuid, false)
                        } catch (e: SQLException) {
                            plugin.getSLF4JLogger().error("Failed to clear operation_in_progress state for {}", player.uniqueId, e)
                        }
                    }
                })
            }
        })
    }

    private fun sendMessage(player: Player, message: Component) {
        Bukkit.getScheduler().runTask(plugin, Runnable { player.sendMessage(message) })
    }

    companion object {
        @JvmField
        val PROCESSING: MutableSet<UUID> = Collections.synchronizedSet(HashSet())
    }
}
