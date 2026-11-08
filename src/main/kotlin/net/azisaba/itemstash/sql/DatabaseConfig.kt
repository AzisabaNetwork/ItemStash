package net.azisaba.itemstash.sql

import org.bukkit.configuration.ConfigurationSection
import java.util.Properties

class DatabaseConfig(section: ConfigurationSection) {
    val driver: String? = section.getString("driver")
    val scheme: String = requireNotNull(section.getString("scheme")) { "scheme" }
    val hostname: String = requireNotNull(section.getString("hostname")) { "hostname" }
    val port: Int = section.getInt("port", 3306)
    val name: String = requireNotNull(section.getString("name")) { "name" }
    val username: String? = section.getString("username")
    val password: String? = section.getString("password")
    val properties: Properties = Properties().apply {
        section.getConfigurationSection("properties")?.getValues(true)?.forEach { (key, value) ->
            setProperty(key, value.toString())
        }
    }

    fun toUrl(): String = "$scheme://$hostname:$port/$name"
}
