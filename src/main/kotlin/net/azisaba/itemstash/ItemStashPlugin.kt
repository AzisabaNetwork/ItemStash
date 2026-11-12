package net.azisaba.itemstash

import net.azisaba.itemstash.util.ItemCodec
import net.azisaba.itemstash.command.ItemStashCommand
import net.azisaba.itemstash.command.PickupStashCommand
import net.azisaba.itemstash.command.StashNotifyCommand
import net.azisaba.itemstash.gui.DeleteConfirmScreen
import net.azisaba.itemstash.gui.PickupStashScreen
import net.azisaba.itemstash.listener.JoinListener
import net.azisaba.itemstash.sql.DBConnector
import net.azisaba.itemstash.sql.DatabaseConfig
import net.azisaba.itemstash.sql.getBlobBytes
import net.azisaba.itemstash.util.ItemUtil
import net.azisaba.itemstash.util.formatDate
import net.azisaba.itemstash.util.plus
import net.azisaba.itemstash.util.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import org.mariadb.jdbc.MariaDbBlob
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Level

class ItemStashPlugin : JavaPlugin(), ItemStash {
    private val sync = Executor { Bukkit.getScheduler().runTask(this, it) }
    private val async = Executor { Bukkit.getScheduler().runTaskAsynchronously(this, it) }

    override fun onEnable() {
        saveDefaultConfig()
        val databaseConfig = DatabaseConfig(requireNotNull(config.getConfigurationSection("database")) { "database" })
        val rows = DBConnector.init(databaseConfig)
        logger.info("Removed $rows expired items")
        server.pluginManager.registerEvents(PickupStashScreen.EventListener(this), this)
        server.pluginManager.registerEvents(DeleteConfirmScreen.EventListener(this), this)
        server.pluginManager.registerEvents(JoinListener(this), this)
        requireNotNull(getCommand("pickupstash")).setExecutor(PickupStashCommand(this))
        requireNotNull(getCommand("itemstash")).setExecutor(ItemStashCommand(this))
        requireNotNull(getCommand("stashnotify")).setExecutor(StashNotifyCommand(this))
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                try {
                    if (DBConnector.isSuppressNotification(player.uniqueId)) continue
                } catch (e: SQLException) {
                    getSLF4JLogger().warn("Failed to check for notification status", e)
                    continue
                }
                forceNotifyStash(player)
            }
        }, 20L * 60 * 10, 20L * 60 * 10)
    }

    fun forceNotifyStash(player: Player) {
        val count = getStashItemCount(player.uniqueId)
        if (count == 0) return
        val exp = getNearestExpirationTime(player.uniqueId)
        player.sendMessage(
            text("Stashに", NamedTextColor.GOLD) + text(count, NamedTextColor.RED) + text("件のアイテムがあります！", NamedTextColor.GOLD)
        )
        player.sendMessage(
            text("受け取るには", NamedTextColor.GOLD) + text("/pickupstash", NamedTextColor.AQUA) + text("を実行してください。", NamedTextColor.GOLD)
        )
        if (exp > 0) {
            player.sendMessage(
                text("直近の有効期限は", NamedTextColor.GOLD) + text(formatDate(exp), NamedTextColor.RED) + text("です。", NamedTextColor.GOLD)
            )
        }
    }

    override fun onDisable() {
        DBConnector.close()
    }

    override fun addItemToStash(player: UUID, itemStack: ItemStack, expiresAt: Long) {
        logger.info("Adding item to stash of $player:")
        ItemUtil.log(logger, itemStack)
        DBConnector.runPrepareStatement("INSERT INTO `stashes` (`uuid`, `item`, `expires_at`, `true_amount`) VALUES (?, ?, ?, ?)") { statement ->
            statement.setString(1, player.toString())
            val amount = itemStack.amount
            // the real amount is stored in `true_amount`; keep the serialized stack within its max stack size
            itemStack.amount = minOf(itemStack.maxStackSize, amount)
            statement.setBlob(2, MariaDbBlob(net.azisaba.itemstash.util.ItemNormalizer.normalize(itemStack).serializeAsBytes()))
            itemStack.amount = amount
            statement.setLong(3, expiresAt)
            statement.setInt(4, amount)
            statement.executeUpdate()
        }
    }

    override fun getStashItemCount(player: UUID): Int =
        DBConnector.getPrepareStatement("SELECT COUNT(*) FROM `stashes` WHERE `uuid` = ?") { statement ->
            statement.setString(1, player.toString())
            statement.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }

    override fun getNearestExpirationTime(player: UUID): Long =
        DBConnector.getPrepareStatement("SELECT `expires_at` FROM `stashes` WHERE `uuid` = ? AND `expires_at` != -1 ORDER BY `expires_at` LIMIT 1") { statement ->
            statement.setString(1, player.toString())
            statement.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) else 0L }
        }

    override fun dumpStash(player: Player): CompletableFuture<Boolean> = dumpStashUntilFull(player)

    private fun dumpStashUntilFull(player: Player): CompletableFuture<Boolean> =
        takeNextStashItems(player).thenCompose { entries ->
            if (entries.isEmpty()) {
                return@thenCompose CompletableFuture.completedFuture(true)
            }
            val items = entries.map { it.item }
            logger.info("Attempting to give ${items.size} item stacks to ${player.name} (${player.uniqueId}):")
            ItemUtil.log(logger, items)
            CompletableFuture.supplyAsync({
                // the entries are already deleted from the database, so an exception here must not lose them:
                // give items one by one and treat any item that failed as "did not fit" so it is put back
                val notFit = HashMap<Int, ItemStack>()
                items.forEachIndexed { index, item ->
                    try {
                        ItemUtil.addItem(player.inventory, item)[0]?.let { notFit[index] = it }
                    } catch (e: Exception) {
                        logger.log(Level.SEVERE, "Failed to give item to ${player.name} (${player.uniqueId}), returning it to the stash", e)
                        notFit[index] = item
                    }
                }
                notFit
            }, sync)
                .thenComposeAsync({ notFit ->
                    if (notFit.isEmpty()) {
                        return@thenComposeAsync dumpStashUntilFull(player)
                    }
                    logger.info("Re-adding ${notFit.size} item stacks to ${player.name} (${player.uniqueId})'s stash:")
                    ItemUtil.log(logger, notFit.values)
                    notFit.forEach { (index, itemStack) ->
                        addItemToStash(player.uniqueId, itemStack, entries[index].expiresAt)
                    }
                    CompletableFuture.completedFuture(false)
                }, async)
        }

    private fun takeNextStashItems(player: Player): CompletableFuture<List<StashEntry>> =
        CompletableFuture.supplyAsync({
            DBConnector.getConnection().use { connection ->
                val statement = connection.createStatement()
                statement.executeUpdate("LOCK TABLES `stashes` WRITE")
                try {
                    val entries = mutableListOf<StashEntry>()
                    // No LIMIT: rows that cannot be decoded are skipped (and kept), so they must not use up the batch
                    connection.prepareStatement("SELECT `item`, `expires_at`, `true_amount` FROM `stashes` WHERE `uuid` = ? ORDER BY IF(`expires_at` = -1, 1, 0), `expires_at`").use { stmt ->
                        stmt.setString(1, player.uniqueId.toString())
                        stmt.executeQuery().use { rs ->
                            while (entries.size < DUMP_BATCH_SIZE && rs.next()) {
                                val trueAmount = rs.getInt("true_amount")
                                val bytes = rs.getBlobBytes("item")
                                val expiresAt = rs.getLong("expires_at")
                                val item = ItemCodec.decode(bytes) ?: continue
                                if (trueAmount > 0) {
                                    item.amount = trueAmount
                                }
                                entries.add(StashEntry(item, bytes, expiresAt))
                            }
                        }
                    }
                    if (entries.isNotEmpty()) {
                        connection.prepareStatement("DELETE FROM `stashes` WHERE `uuid` = ? AND `item` = ? ORDER BY IF(`expires_at` = -1, 1, 0), `expires_at` LIMIT 1").use { stmt ->
                            for (entry in entries) {
                                stmt.setString(1, player.uniqueId.toString())
                                stmt.setBlob(2, MariaDbBlob(entry.bytes))
                                stmt.executeUpdate()
                            }
                        }
                    }
                    entries
                } finally {
                    statement.executeUpdate("UNLOCK TABLES")
                }
            }
        }, async)

    private class StashEntry(val item: ItemStack, val bytes: ByteArray, val expiresAt: Long)

    companion object {
        private const val DUMP_BATCH_SIZE = 20
    }
}
