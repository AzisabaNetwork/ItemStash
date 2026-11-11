package net.azisaba.itemstash.util

import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.logging.Level
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Decodes stash rows, including rows written on 1.15.2.
 *
 * `ItemStack.deserializeBytes` runs the stored NBT through Minecraft's DataFixer, but Paper 1.21.11 rejects some
 * 1.15.2 data outright, most notably the camelCase attribute names that MythicMobs 4 items carry
 * (`generic.attackDamage` -> "Not a valid resource location"). One such row used to throw out of the whole query,
 * so the player could not receive any of their stash. Such rows are now fixed up and decoded again; a row that still
 * cannot be read is skipped (and kept in the database) instead of failing the others.
 */
object ItemCodec {
    private const val DATA_VERSION_1_16 = 2566
    private var failures = 0

    /** @return the item, or null if the row cannot be decoded even after the legacy fix-ups */
    fun decode(bytes: ByteArray): ItemStack? {
        val first = try {
            return ItemStack.deserializeBytes(bytes)
        } catch (e: Exception) {
            e
        }
        val fixed = try {
            fixLegacy(bytes)
        } catch (e: Exception) {
            first.addSuppressed(e)
            null
        }
        if (fixed != null) {
            try {
                return ItemStack.deserializeBytes(fixed)
            } catch (e: Exception) {
                first.addSuppressed(e)
            }
        }
        if (failures++ < 20) {
            Bukkit.getLogger().log(Level.WARNING, "[ItemStash] Could not decode a stash item; it is kept in the database", first)
        }
        return null
    }

    /**
     * Applies the fix-ups the DataFixer is missing for 1.15.2 items. Returns null when the data is not a pre-1.16 item
     * or nothing had to change.
     */
    internal fun fixLegacy(bytes: ByteArray): ByteArray? {
        val root = Nbt.read(bytes) as? MutableMap<String, Nbt.Tag> ?: return null
        val version = (root["DataVersion"]?.value as? Number)?.toInt() ?: 0
        if (version >= DATA_VERSION_1_16) return null
        var changed = false
        @Suppress("UNCHECKED_CAST")
        val tag = root["tag"]?.value as? MutableMap<String, Nbt.Tag>
        if (tag != null) {
            if (!tag.containsKey("SkullOwner") && tag["SkullProfile"]?.type == Nbt.COMPOUND) {
                tag["SkullOwner"] = tag.remove("SkullProfile")!!
                changed = true
            }
        }
        changed = renameAttributes(root, 0) || changed
        return if (changed) Nbt.write(root) else null
    }

    /** `generic.attackDamage` -> `minecraft:generic.attack_damage` (the 1.16 name the later fixes upgrade). */
    internal fun legacyAttributeKey(raw: String): String {
        val path = raw.trim().substringAfter(':')
        return "minecraft:" + path.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    }

    @Suppress("UNCHECKED_CAST")
    private fun renameAttributes(node: Any?, depth: Int): Boolean {
        if (depth > 64) return false
        var changed = false
        when (node) {
            is MutableMap<*, *> -> {
                val map = node as MutableMap<String, Nbt.Tag>
                (map["AttributeModifiers"]?.value as? List<Nbt.Tag>)?.forEach { modifier ->
                    val m = modifier.value as? MutableMap<String, Nbt.Tag> ?: return@forEach
                    val name = m["AttributeName"]?.value as? String ?: return@forEach
                    val renamed = legacyAttributeKey(name)
                    if (renamed != name) {
                        m["AttributeName"] = Nbt.Tag(Nbt.STRING, renamed)
                        changed = true
                    }
                }
                for (value in map.values) changed = renameAttributes(value.value, depth + 1) || changed
            }
            is List<*> -> for (value in node) changed = renameAttributes((value as? Nbt.Tag)?.value, depth + 1) || changed
        }
        return changed
    }

    /** Minimal type-preserving NBT codec for gzip-compressed `serializeAsBytes` data. */
    internal object Nbt {
        const val STRING: Byte = 8
        const val LIST: Byte = 9
        const val COMPOUND: Byte = 10

        class Tag(val type: Byte, val value: Any?)

        /** @return the root compound as a mutable map */
        fun read(bytes: ByteArray): Any? {
            DataInputStream(GZIPInputStream(ByteArrayInputStream(bytes))).use { input ->
                val type = input.readByte()
                if (type != COMPOUND) return null
                input.readUTF()
                return readPayload(input, type, 0)
            }
        }

        fun write(root: Map<String, Tag>): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(GZIPOutputStream(out)).use { output ->
                output.writeByte(COMPOUND.toInt())
                output.writeUTF("")
                writePayload(output, COMPOUND, root)
            }
            return out.toByteArray()
        }

        private fun readPayload(input: DataInputStream, type: Byte, depth: Int): Any? {
            require(depth <= 512) { "NBT too deep" }
            return when (type.toInt()) {
                1 -> input.readByte()
                2 -> input.readShort()
                3 -> input.readInt()
                4 -> input.readLong()
                5 -> input.readFloat()
                6 -> input.readDouble()
                7 -> ByteArray(input.readInt()).also { input.readFully(it) }
                8 -> input.readUTF()
                9 -> {
                    val elementType = input.readByte()
                    val size = input.readInt()
                    MutableList(size) { Tag(elementType, readPayload(input, elementType, depth + 1)) }.let {
                        ListValue(elementType, it)
                    }
                }
                10 -> {
                    val map = LinkedHashMap<String, Tag>()
                    while (true) {
                        val childType = input.readByte()
                        if (childType.toInt() == 0) break
                        val name = input.readUTF()
                        map[name] = Tag(childType, readPayload(input, childType, depth + 1))
                    }
                    map
                }
                11 -> IntArray(input.readInt()) { input.readInt() }
                12 -> LongArray(input.readInt()) { input.readLong() }
                else -> throw IllegalArgumentException("Unknown NBT type $type")
            }
        }

        /** A list that remembers its element type (needed to write empty lists back). */
        class ListValue(val elementType: Byte, elements: MutableList<Tag>) : MutableList<Tag> by elements

        @Suppress("UNCHECKED_CAST")
        private fun writePayload(output: DataOutputStream, type: Byte, value: Any?) {
            when (type.toInt()) {
                1 -> output.writeByte((value as Number).toInt())
                2 -> output.writeShort((value as Number).toInt())
                3 -> output.writeInt((value as Number).toInt())
                4 -> output.writeLong((value as Number).toLong())
                5 -> output.writeFloat((value as Number).toFloat())
                6 -> output.writeDouble((value as Number).toDouble())
                7 -> (value as ByteArray).let { output.writeInt(it.size); output.write(it) }
                8 -> output.writeUTF(value as String)
                9 -> {
                    val list = value as ListValue
                    output.writeByte(list.elementType.toInt())
                    output.writeInt(list.size)
                    for (element in list) writePayload(output, list.elementType, element.value)
                }
                10 -> {
                    for ((name, tag) in value as Map<String, Tag>) {
                        output.writeByte(tag.type.toInt())
                        output.writeUTF(name)
                        writePayload(output, tag.type, tag.value)
                    }
                    output.writeByte(0)
                }
                11 -> (value as IntArray).let { output.writeInt(it.size); it.forEach(output::writeInt) }
                12 -> (value as LongArray).let { output.writeInt(it.size); it.forEach(output::writeLong) }
                else -> throw IllegalArgumentException("Unknown NBT type $type")
            }
        }
    }
}
