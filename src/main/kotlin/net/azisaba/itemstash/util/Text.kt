package net.azisaba.itemstash.util

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss").withZone(ZoneId.systemDefault())

fun text(content: Any, color: TextColor? = null, vararg decorations: TextDecoration): Component =
    Component.text(content.toString(), color, *decorations)

operator fun Component.plus(other: Component): Component = append(other)

/** Item names and lore are italic by default; legacy color codes used to reset that. */
fun Component.noItalic(): Component = decoration(TextDecoration.ITALIC, false)

fun formatDate(epochMillis: Long): String = DATE_FORMAT.format(Instant.ofEpochMilli(epochMillis))
