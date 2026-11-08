package net.azisaba.itemstash.command

import net.azisaba.itemstash.ItemStashPlugin
import net.azisaba.itemstash.sql.DBConnector
import net.azisaba.itemstash.sql.getBlobBytes
import net.azisaba.itemstash.util.plus
import net.azisaba.itemstash.util.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.mariadb.jdbc.MariaDbBlob

class ItemStashCommand(private val plugin: ItemStashPlugin) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(text("aaaa", NamedTextColor.RED))
            return true
        }
        when (args[0]) {
            "add" -> {
                val stack = heldItem(sender) ?: return true
                val player = targetPlayer(sender, args, "/itemstash add <player> [count]") ?: return true
                val count = if (args.size == 2) 1 else args[2].toInt()
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    val amount = stack.amount * count
                    stack.amount = amount
                    plugin.addItemToStash(player.uniqueId, stack)
                    stack.amount = amount / count
                    sender.sendMessage(text("ﾖｼ!", NamedTextColor.GREEN))
                })
            }

            "count" -> {
                val player = targetPlayer(sender, args, "/itemstash count <player>") ?: return true
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    val count = plugin.getStashItemCount(player.uniqueId)
                    sender.sendMessage(
                        text(player.name, NamedTextColor.RED) + text(" has ", NamedTextColor.GOLD) +
                            text(count, NamedTextColor.GREEN) + text(" items in stash", NamedTextColor.GOLD)
                    )
                })
            }

            "removeSimilar" -> {
                val stack = heldItem(sender) ?: return true
                val player = targetPlayer(sender, args, "/itemstash removeSimilar <player>") ?: return true
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    DBConnector.use { connection ->
                        val toRemove = mutableListOf<ByteArray>()
                        connection.prepareStatement("SELECT `item` FROM `stashes` WHERE `uuid` = ?").use { stmt ->
                            stmt.setString(1, player.uniqueId.toString())
                            stmt.executeQuery().use { rs ->
                                while (rs.next()) {
                                    val bytes = rs.getBlobBytes("item")
                                    if (ItemStack.deserializeBytes(bytes).isSimilar(stack) && toRemove.none { it.contentEquals(bytes) }) {
                                        toRemove.add(bytes)
                                    }
                                }
                            }
                        }
                        plugin.logger.info("Collected ${toRemove.size} items to remove")
                        connection.prepareStatement("DELETE FROM `stashes` WHERE `uuid` = ? AND `item` = ?").use { stmt ->
                            for (bytes in toRemove) {
                                stmt.setString(1, player.uniqueId.toString())
                                stmt.setBlob(2, MariaDbBlob(bytes))
                                stmt.executeUpdate()
                            }
                        }
                    }
                    sender.sendMessage(text("ﾖｼ!", NamedTextColor.GREEN))
                })
            }

            "clearStash" -> {
                val player = targetPlayer(sender, args, "/itemstash clearStash <player>") ?: return true
                Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
                    DBConnector.runPrepareStatement("DELETE FROM `stashes` WHERE `uuid` = ?") { stmt ->
                        stmt.setString(1, player.uniqueId.toString())
                        stmt.executeUpdate()
                    }
                    sender.sendMessage(text("ﾖｼ!", NamedTextColor.GREEN))
                })
            }

            "unlock" -> {
                val player = targetPlayer(sender, args, "/itemstash unlock <player>") ?: return true
                PickupStashCommand.PROCESSING.remove(player.uniqueId)
                DBConnector.setOperationInProgress(player.uniqueId, false)
                player.sendMessage(text("ﾖｼ!", NamedTextColor.GREEN))
            }
        }
        return true
    }

    /** Returns the item in the sender's main hand, or null (after sending an error) if unavailable. */
    private fun heldItem(sender: CommandSender): ItemStack? {
        if (sender !is Player) {
            sender.sendMessage(text("you're not a player", NamedTextColor.RED))
            return null
        }
        val stack = sender.inventory.itemInMainHand
        if (stack.type == Material.AIR || stack.amount == 0) {
            sender.sendMessage(text("you are not holding an item", NamedTextColor.RED))
            return null
        }
        return stack
    }

    /** Returns the online player named by args[1], or null (after sending an error) if missing. */
    private fun targetPlayer(sender: CommandSender, args: Array<out String>, usage: String): Player? {
        if (args.size == 1) {
            sender.sendMessage(text(usage, NamedTextColor.RED))
            return null
        }
        val player = Bukkit.getPlayerExact(args[1])
        if (player == null) {
            sender.sendMessage(text("no such player: " + args[1], NamedTextColor.RED))
        }
        return player
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size == 1) {
            return listOf("add", "count", "removeSimilar", "unlock", "clearStash").filter { it.startsWith(args[0]) }
        }
        if (args.size == 2 && args[0] in listOf("add", "count", "removeSimilar", "unlock")) {
            return Bukkit.getOnlinePlayers().map { it.name }.filter { it.lowercase().startsWith(args[1].lowercase()) }
        }
        return emptyList()
    }
}
