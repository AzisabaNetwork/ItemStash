package net.azisaba.itemstash.command

import net.azisaba.itemstash.ItemStashPlugin
import net.azisaba.itemstash.sql.DBConnector
import net.azisaba.itemstash.util.text
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.command.TabExecutor
import org.bukkit.entity.Player

class StashNotifyCommand(private val plugin: ItemStashPlugin) : TabExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) return true
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val current = DBConnector.isSuppressNotification(sender.uniqueId)
            DBConnector.setSuppressNotification(sender.uniqueId, !current)
            if (current) {
                // don't suppress (suppress_notification = false)
                sender.sendMessage(text("Stash通知をオンにしました。", NamedTextColor.GREEN))
            } else {
                // do suppress (suppress_notification = true)
                sender.sendMessage(text("Stash通知をオフにしました。", NamedTextColor.GREEN))
            }
        })
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        emptyList()
}
