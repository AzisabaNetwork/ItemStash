package net.azisaba.itemstash.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ItemCodecTest {
    private fun tag(type: Byte, value: Any?) = ItemCodec.Nbt.Tag(type, value)

    private fun legacySword(dataVersion: Int): ByteArray {
        val modifier = linkedMapOf(
            "AttributeName" to tag(8, "generic.attackDamage"),
            "Name" to tag(8, "generic.attackDamage"),
            "Amount" to tag(6, 10.0),
            "Operation" to tag(3, 0),
            "UUIDMost" to tag(4, 1L),
            "UUIDLeast" to tag(4, 2L),
        )
        val itemTag = linkedMapOf<String, ItemCodec.Nbt.Tag>(
            "MYTHIC_TYPE" to tag(8, "SkeletonKingSword"),
            "AttributeModifiers" to tag(9, ItemCodec.Nbt.ListValue(10, mutableListOf(tag(10, modifier)))),
            "Empty" to tag(9, ItemCodec.Nbt.ListValue(0, mutableListOf())),
            "SkullProfile" to tag(10, linkedMapOf("Name" to tag(8, "abc"))),
        )
        val root = linkedMapOf(
            "id" to tag(8, "minecraft:iron_sword"),
            "Count" to tag(1, 1.toByte()),
            "tag" to tag(10, itemTag),
            "DataVersion" to tag(3, dataVersion),
        )
        return ItemCodec.Nbt.write(root)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun renamesLegacyAttributesAndSkullProfile() {
        val fixed = ItemCodec.fixLegacy(legacySword(2230))!!
        val root = ItemCodec.Nbt.read(fixed) as Map<String, ItemCodec.Nbt.Tag>
        val itemTag = root["tag"]!!.value as Map<String, ItemCodec.Nbt.Tag>
        val modifier = (itemTag["AttributeModifiers"]!!.value as List<ItemCodec.Nbt.Tag>)[0].value as Map<String, ItemCodec.Nbt.Tag>
        assertEquals("minecraft:generic.attack_damage", modifier["AttributeName"]!!.value)
        assertEquals("generic.attackDamage", modifier["Name"]!!.value)
        assertEquals(10.0, modifier["Amount"]!!.value)
        assertEquals("SkeletonKingSword", itemTag["MYTHIC_TYPE"]!!.value)
        assertNull(itemTag["SkullProfile"])
        assertNotNull(itemTag["SkullOwner"])
        assertEquals(0, (itemTag["Empty"]!!.value as List<*>).size)
        assertEquals(2230, root["DataVersion"]!!.value)
    }

    @Test
    fun leavesModernDataAlone() {
        assertNull(ItemCodec.fixLegacy(legacySword(4671)))
    }

    @Test
    fun attributeKey() {
        assertEquals("minecraft:generic.max_health", ItemCodec.legacyAttributeKey("generic.maxHealth"))
        assertEquals("minecraft:generic.armor", ItemCodec.legacyAttributeKey("minecraft:generic.armor"))
    }
}
