package net.azisaba.itemstash.util;

import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Brings items that look the same but were converted from 1.15.2 in different ways to one and the same data.
 * <p>
 * THIS FILE IS SHARED: AziSync, EnderChestPlus, StorageBox, ItemStash and SearchableInfiniteShop each carry a copy that must stay identical
 * apart from the package line. Change all of them together, otherwise the plugins stop agreeing on what "the same
 * item" is and items no longer stack or move between them.
 * <p>
 * The same 1.15.2 item ends up with different components depending on who converted it:
 * <ul>
 *   <li>Minecraft's DataConverter ({@code ItemStack.deserializeBytes}: AziSync inventories, Storage Box contents,
 *       ItemStash) keeps names and lore as text with "§" codes ({@code "§d§lName"}), drops the hide flags of
 *       components the item does not have, and adds no profile to plain player heads.</li>
 *   <li>Bukkit's YAML reader (EnderChestPlus, shops) builds real text components
 *       ({@code {extra:[{color:"light_purple",bold:1b,...}],text:""}}), but leaves a line as "§" text when it happens
 *       to parse as lenient JSON, keeps every hide flag and adds an empty profile to player heads.</li>
 * </ul>
 * Both render identically, yet {@link ItemStack#isSimilar} tells them apart. {@link #normalize} rewrites only those
 * leftovers into the form the Bukkit API writes on 1.21.11 ({@code setDisplayName}/{@code setLore}), so an item made
 * on 1.21.11 is never changed by it:
 * <ul>
 *   <li>a name or lore line stored as a bare string becomes the text component Bukkit builds from that string;</li>
 *   <li>an empty {@code minecraft:profile}, a {@code minecraft:dyed_color} of undyed leather and a
 *       {@code minecraft:block_entity_data} holding nothing but a position (left on 1.15.2 shields) are removed;</li>
 *   <li>trailing spaces of such a name or lore line are dropped (Bukkit's YAML reader loses them);</li>
 *   <li>the MythicMobs 4 {@code MYTHIC_TYPE} tag is removed once the item carries the same ID as
 *       {@code mythicmobs:type} (the only one MythicMobs 5 reads);</li>
 *   <li>the same is done for items inside shulker boxes, bundles and crossbows.</li>
 * </ul>
 * Attribute modifiers are not touched: their IDs differ per conversion, and MythicMobs items are matched by MMID.
 * <p>
 * Works on Paper 1.21.5+ (where text components are NBT). On anything else every method returns its input.
 * Thread-safe; does not touch the world.
 */
public final class ItemNormalizer {
    /** Bump when the result of {@link #normalize} changes, so stored data can be normalized again. */
    public static final int VERSION = 1;

    /** 1.21.5: text components are stored as NBT instead of JSON strings. */
    private static final int MIN_DATA_VERSION = 4325;
    private static final String COMPONENTS = "components";
    private static final String CUSTOM_NAME = "minecraft:custom_name";
    private static final String LORE = "minecraft:lore";
    private static final String PROFILE = "minecraft:profile";
    private static final String CUSTOM_DATA = "minecraft:custom_data";
    private static final String DYED_COLOR = "minecraft:dyed_color";
    private static final String BLOCK_ENTITY_DATA = "minecraft:block_entity_data";
    private static final List<String> POSITION_KEYS = Arrays.asList("id", "x", "y", "z");
    /** The color of undyed leather armor. */
    private static final int DEFAULT_LEATHER_COLOR = 0xA06540;
    private static final String PDC = "PublicBukkitValues";
    private static final int MAX_DEPTH = 16;
    private static final int CACHE_SIZE = 4096;

    private static final Method SERIALIZE = method("serializeAsBytes");
    private static final Method DESERIALIZE = method("deserializeBytes", byte[].class);
    private static final Object UNCHANGED = new Object();

    private static final Map<String, Object> NAME_CACHE = cache();
    private static final Map<String, Object> LORE_CACHE = cache();

    private ItemNormalizer() {}

    private static Method method(String name, Class<?>... parameters) {
        try {
            return ItemStack.class.getMethod(name, parameters);
        } catch (Throwable e) {
            return null; // not Paper, or older than 1.16
        }
    }

    private static Map<String, Object> cache() {
        return Collections.synchronizedMap(new LinkedHashMap<String, Object>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                return size() > CACHE_SIZE;
            }
        });
    }

    /** Whether this server can normalize items at all (Paper with {@code serializeAsBytes}). */
    public static boolean isSupported() {
        return SERIALIZE != null && DESERIALIZE != null;
    }

    /**
     * The item in its normalized form.
     *
     * @return {@code item} itself when nothing had to change (or it could not be normalized), otherwise a new item
     */
    public static ItemStack normalize(ItemStack item) {
        return normalize(item, 0);
    }

    /**
     * Same as {@link #normalize(ItemStack)} for an item that was just converted from 1.15.2 NBT by the DataConverter.
     *
     * @param legacyHideFlags the {@code HideFlags} of the 1.15.2 tag (0 if none). The DataConverter drops the flags
     *                        of components the item does not have (e.g. "hide attributes" on a sword without custom
     *                        attributes, which then shows its vanilla attack damage); they are added back the way
     *                        Bukkit stores them, as on items read from YAML.
     */
    public static ItemStack normalize(ItemStack item, int legacyHideFlags) {
        if (item == null || !isSupported()) return item;
        ItemStack result = item;
        try {
            if (item.getType() != Material.AIR && item.hasItemMeta()) {
                Map<String, Object> root = readRoot(item);
                if (root == null) return item;
                if (normalizeItem(root, 0)) {
                    ItemStack fixed = (ItemStack) DESERIALIZE.invoke(null, (Object) writeRoot(root));
                    if (fixed != null && fixed.getType() == item.getType()) result = fixed;
                }
            }
            if (legacyHideFlags != 0) result = addLegacyHideFlags(result, legacyHideFlags);
        } catch (Throwable e) {
            return item;
        }
        return result;
    }

    /** Normalizes every item of the array in place. Returns the number of changed items. */
    public static int normalizeAll(ItemStack[] items) {
        if (items == null || !isSupported()) return 0;
        int changed = 0;
        for (int i = 0; i < items.length; i++) {
            ItemStack item = items[i];
            if (item == null) continue;
            ItemStack fixed = normalize(item);
            if (fixed != item) {
                items[i] = fixed;
                changed++;
            }
        }
        return changed;
    }

    /** {@link ItemStack#isSimilar} on the normalized forms of both items. */
    public static boolean isSimilar(ItemStack a, ItemStack b) {
        if (a == null || b == null) return false;
        if (a.isSimilar(b)) return true;
        if (a.getType() != b.getType()) return false;
        ItemStack na = normalize(a);
        ItemStack nb = normalize(b);
        return (na != a || nb != b) && na.isSimilar(nb);
    }

    /**
     * Whether two items have the same material, texture and lore: lore, custom model data, item model, damage,
     * dye color, shield/banner colors and head skin. For items that are known to be the same kind (same MMID) but whose other data
     * differs; any difference in texture or lore makes them different items.
     */
    public static boolean sameTextureAndLore(ItemStack a, ItemStack b) {
        if (a == null || b == null || a.getType() != b.getType()) return false;
        try {
            if (isSupported()) {
                Map<String, Object> ca = looks(a);
                Map<String, Object> cb = looks(b);
                if (ca != null && cb != null) return tagEquals(ca, cb);
            }
        } catch (Throwable ignored) {
            // compare through the API below
        }
        return sameLoreAndDamage(a, b);
    }

    /** For servers older than 1.21.5, where the components cannot be read: lore and damage through the Bukkit API. */
    @SuppressWarnings("deprecation")
    private static boolean sameLoreAndDamage(ItemStack a, ItemStack b) {
        if (a.getDurability() != b.getDurability()) return false;
        ItemMeta ma = a.hasItemMeta() ? a.getItemMeta() : null;
        ItemMeta mb = b.hasItemMeta() ? b.getItemMeta() : null;
        List<String> la = ma != null && ma.hasLore() ? ma.getLore() : null;
        List<String> lb = mb != null && mb.hasLore() ? mb.getLore() : null;
        return Objects.equals(la, lb);
    }

    private static final String[] LOOK_COMPONENTS = {
            LORE, "minecraft:custom_model_data", "minecraft:item_model", "minecraft:damage", DYED_COLOR,
            "minecraft:base_color", "minecraft:banner_patterns"
    };
    private static final Pattern SKIN_URL = Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"");

    private static Map<String, Object> looks(ItemStack item) throws Exception {
        Map<String, Object> looks = new LinkedHashMap<String, Object>();
        if (!item.hasItemMeta()) return looks;
        Map<String, Object> root = readRoot(item);
        if (root == null) return null;
        normalizeItem(root, 0);
        Map<String, Object> components = compound(root.get(COMPONENTS));
        if (components == null) return looks;
        for (String key : LOOK_COMPONENTS) {
            Object value = components.get(key);
            if (value == null) continue;
            if (key.equals("minecraft:damage") && value instanceof Number && ((Number) value).intValue() == 0) continue;
            if (key.equals(LORE) && value instanceof List && ((List<?>) value).isEmpty()) continue;
            looks.put(key, value);
        }
        String skin = skin(compound(components.get(PROFILE)));
        if (skin != null) looks.put(PROFILE, skin);
        return looks;
    }

    /** The skin URL of a profile component (what decides the texture of a head), or its raw value. */
    private static String skin(Map<String, Object> profile) {
        if (profile == null) return null;
        Object properties = profile.get("properties");
        if (properties instanceof List) {
            for (Object element : (List<?>) properties) {
                Map<String, Object> property = compound(element);
                if (property == null || !"textures".equals(property.get("name"))) continue;
                Object value = property.get("value");
                if (!(value instanceof String)) continue;
                try {
                    String json = new String(Base64.getDecoder().decode((String) value), StandardCharsets.UTF_8);
                    Matcher matcher = SKIN_URL.matcher(json);
                    if (matcher.find()) return matcher.group(1);
                } catch (IllegalArgumentException ignored) {
                    // not base64: compare the raw value
                }
                return (String) value;
            }
        }
        Object name = profile.get("name");
        return name instanceof String && !((String) name).isEmpty() ? "name:" + name : null;
    }

    // ---- normalization ----

    private static Map<String, Object> readRoot(ItemStack item) throws Exception {
        Object root = read((byte[]) SERIALIZE.invoke(item));
        Map<String, Object> map = compound(root);
        if (map == null) return null;
        Object version = map.get("DataVersion");
        if (!(version instanceof Number) || ((Number) version).intValue() < MIN_DATA_VERSION) return null;
        return map;
    }

    /** @param item an item compound in the 1.21.5+ format ({@code id}, {@code count}, {@code components}) */
    private static boolean normalizeItem(Map<String, Object> item, int depth) {
        Map<String, Object> components = compound(item.get(COMPONENTS));
        if (components == null || depth > MAX_DEPTH) return false;
        boolean changed = false;

        Object name = components.get(CUSTOM_NAME);
        if (name instanceof String) {
            Object fixed = bukkitText((String) name, false);
            if (fixed != null) {
                components.put(CUSTOM_NAME, fixed);
                changed = true;
            }
        } else if (trimTrailingSpaces(name)) {
            changed = true;
        }

        Object lore = components.get(LORE);
        if (lore instanceof NbtList) {
            NbtList lines = (NbtList) lore;
            NbtList fixedLines = null;
            for (int i = 0; i < lines.size(); i++) {
                Object line = lines.get(i);
                if (!(line instanceof String)) {
                    if (trimTrailingSpaces(line)) changed = true;
                    continue;
                }
                Object fixed = bukkitText((String) line, true);
                if (fixed == null) continue;
                if (fixedLines == null) fixedLines = new NbtList(lines);
                fixedLines.set(i, fixed);
            }
            if (fixedLines != null) {
                components.put(LORE, fixedLines);
                changed = true;
            }
        }

        Map<String, Object> profile = compound(components.get(PROFILE));
        if (profile != null && profile.isEmpty()) {
            components.remove(PROFILE);
            changed = true;
        }

        // the DataConverter turns the 1.15.2 "hide dye" flag into an explicit default color, even on non-leather items
        Object dye = components.get(DYED_COLOR);
        if (dye instanceof Integer && (Integer) dye == DEFAULT_LEATHER_COLOR) {
            components.remove(DYED_COLOR);
            changed = true;
        }

        // a 1.15.2 shield kept its banner in a BlockEntityTag; the DataConverter moves the colors out of it and
        // leaves {id:"minecraft:banner",x:0,y:0,z:0} behind, which says nothing
        Map<String, Object> blockEntity = compound(components.get(BLOCK_ENTITY_DATA));
        if (blockEntity != null && POSITION_KEYS.containsAll(blockEntity.keySet())) {
            components.remove(BLOCK_ENTITY_DATA);
            changed = true;
        }

        Map<String, Object> customData = compound(components.get(CUSTOM_DATA));
        if (customData != null) {
            Object legacyId = customData.get("MYTHIC_TYPE");
            Map<String, Object> pdc = compound(customData.get(PDC));
            Object id = pdc == null ? null : pdc.get("mythicmobs:type");
            if (legacyId instanceof String && id instanceof String
                    && ((String) legacyId).trim().equals(((String) id).trim())) {
                customData.remove("MYTHIC_TYPE");
                changed = true;
            }
            if (customData.isEmpty()) {
                components.remove(CUSTOM_DATA);
                changed = true;
            }
        }

        Object container = components.get("minecraft:container");
        if (container instanceof List) {
            for (Object slot : (List<?>) container) {
                Map<String, Object> entry = compound(slot);
                Map<String, Object> nested = entry == null ? null : compound(entry.get("item"));
                if (nested != null && normalizeItem(nested, depth + 1)) changed = true;
            }
        }
        for (String key : new String[]{"minecraft:bundle_contents", "minecraft:charged_projectiles"}) {
            Object list = components.get(key);
            if (!(list instanceof List)) continue;
            for (Object element : (List<?>) list) {
                Map<String, Object> nested = compound(element);
                if (nested != null && normalizeItem(nested, depth + 1)) changed = true;
            }
        }

        if (components.isEmpty()) {
            item.remove(COMPONENTS);
            changed = true;
        }
        return changed;
    }

    /**
     * Drops the trailing spaces of a text component in the form Bukkit builds ({@code {extra:[...],text:""}}).
     * Bukkit's YAML reader loses them on some lines and keeps them on others, and they are invisible.
     */
    private static boolean trimTrailingSpaces(Object component) {
        Map<String, Object> map = compound(component);
        if (map == null || map.size() != 2 || !"".equals(map.get("text")) || !(map.get("extra") instanceof NbtList)) return false;
        NbtList extra = (NbtList) map.get("extra");
        boolean changed = false;
        while (!extra.isEmpty()) {
            int last = extra.size() - 1;
            Object run = extra.get(last);
            Map<String, Object> styled = compound(run);
            Object text = styled == null ? run : styled.get("text");
            if (!(text instanceof String)) break;
            if (styled != null && (styled.containsKey("extra") || styled.containsKey("click_event") || styled.containsKey("hover_event"))) break;
            String trimmed = stripTrailingSpaces((String) text);
            if (trimmed.isEmpty()) {
                if (last == 0) break; // nothing but spaces: leave it
                extra.remove(last); // a run of spaces only (or an empty one) at the end
                changed = true;
                continue;
            }
            if (trimmed.length() != ((String) text).length()) {
                if (styled == null) extra.set(last, trimmed);
                else styled.put("text", trimmed);
                changed = true;
            }
            break;
        }
        return changed;
    }

    private static String stripTrailingSpaces(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') end--;
        return text.substring(0, end);
    }

    /**
     * The text component the Bukkit API stores for a legacy ("§") string, as NBT. Null when it is that very string.
     */
    private static Object bukkitText(String legacy, boolean lore) {
        Map<String, Object> cache = lore ? LORE_CACHE : NAME_CACHE;
        Object cached = cache.get(legacy);
        if (cached == null) {
            cached = UNCHANGED;
            // Bukkit's YAML reader loses trailing spaces (they are invisible), so they are dropped everywhere
            String text = stripTrailingSpaces(legacy);
            try {
                ItemStack scratch = new ItemStack(Material.PAPER);
                ItemMeta meta = scratch.getItemMeta();
                if (meta != null) {
                    if (lore) meta.setLore(Collections.singletonList(text));
                    else meta.setDisplayName(text);
                    scratch.setItemMeta(meta);
                    Map<String, Object> root = compound(read((byte[]) SERIALIZE.invoke(scratch)));
                    Map<String, Object> components = root == null ? null : compound(root.get(COMPONENTS));
                    Object built = null;
                    if (components != null) {
                        if (lore) {
                            Object lines = components.get(LORE);
                            if (lines instanceof List && ((List<?>) lines).size() == 1) built = ((List<?>) lines).get(0);
                        } else {
                            built = components.get(CUSTOM_NAME);
                        }
                    }
                    if (built != null) {
                        trimTrailingSpaces(built);
                        if (!built.equals(legacy)) cached = built;
                    }
                }
            } catch (Throwable ignored) {
                // keep the string
            }
            cache.put(legacy, cached);
        }
        return cached == UNCHANGED ? null : copy(cached);
    }

    private static final String[][] HIDE_FLAGS = {
            {"HIDE_ENCHANTS"}, {"HIDE_ATTRIBUTES"}, {"HIDE_UNBREAKABLE"}, {"HIDE_DESTROYS"}, {"HIDE_PLACED_ON"},
            // 1.20.5 renamed HIDE_POTION_EFFECTS; Paper adds HIDE_STORED_ENCHANTS to pre-1.20.5 items that had it
            {"HIDE_ADDITIONAL_TOOLTIP", "HIDE_STORED_ENCHANTS"}, {"HIDE_DYE"}
    };

    private static ItemStack addLegacyHideFlags(ItemStack item, int hideFlags) {
        if (item.getType() == Material.AIR) return item;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;
        boolean changed = false;
        for (int bit = 0; bit < HIDE_FLAGS.length; bit++) {
            if ((hideFlags & (1 << bit)) == 0) continue;
            for (String name : HIDE_FLAGS[bit]) {
                ItemFlag flag;
                try {
                    flag = ItemFlag.valueOf(name);
                } catch (IllegalArgumentException e) {
                    continue; // not on this version
                }
                if (meta.hasItemFlag(flag)) continue;
                meta.addItemFlags(flag);
                changed = true;
            }
        }
        if (!changed) return item;
        ItemStack fixed = item.clone();
        fixed.setItemMeta(meta);
        return fixed;
    }

    // ---- NBT ----
    // Tags are Byte, Short, Integer, Long, Float, Double, String, byte[], int[], long[],
    // NbtList and LinkedHashMap<String, Object> (compound).

    /** A list tag; remembers its element type so an empty list is written back unchanged. */
    private static final class NbtList extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
        byte type;

        NbtList(byte type) {
            this.type = type;
        }

        NbtList(NbtList other) {
            super(other);
            this.type = other.type;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compound(Object tag) {
        return tag instanceof Map ? (Map<String, Object>) tag : null;
    }

    private static Object copy(Object tag) {
        if (tag instanceof Map) {
            Map<String, Object> copy = new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry : compound(tag).entrySet()) copy.put(entry.getKey(), copy(entry.getValue()));
            return copy;
        }
        if (tag instanceof NbtList) {
            NbtList list = (NbtList) tag;
            NbtList copy = new NbtList(list.type);
            for (Object element : list) copy.add(copy(element));
            return copy;
        }
        if (tag instanceof byte[]) return ((byte[]) tag).clone();
        if (tag instanceof int[]) return ((int[]) tag).clone();
        if (tag instanceof long[]) return ((long[]) tag).clone();
        return tag;
    }

    private static boolean tagEquals(Object a, Object b) {
        if (a instanceof Map && b instanceof Map) {
            Map<String, Object> ma = compound(a);
            Map<String, Object> mb = compound(b);
            if (ma.size() != mb.size()) return false;
            for (Map.Entry<String, Object> entry : ma.entrySet()) {
                if (!mb.containsKey(entry.getKey()) || !tagEquals(entry.getValue(), mb.get(entry.getKey()))) return false;
            }
            return true;
        }
        if (a instanceof List && b instanceof List) {
            List<?> la = (List<?>) a;
            List<?> lb = (List<?>) b;
            if (la.size() != lb.size()) return false;
            for (int i = 0; i < la.size(); i++) if (!tagEquals(la.get(i), lb.get(i))) return false;
            return true;
        }
        if (a instanceof byte[] && b instanceof byte[]) return Arrays.equals((byte[]) a, (byte[]) b);
        if (a instanceof int[] && b instanceof int[]) return Arrays.equals((int[]) a, (int[]) b);
        if (a instanceof long[] && b instanceof long[]) return Arrays.equals((long[]) a, (long[]) b);
        return Objects.equals(a, b);
    }

    private static Object read(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
            byte type = in.readByte();
            if (type == 0) return null;
            in.readUTF();
            return readPayload(in, type, 0);
        }
    }

    private static Object readPayload(DataInputStream in, byte type, int depth) throws IOException {
        if (depth > 512) throw new IOException("NBT is nested too deeply");
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                byte[] value = new byte[in.readInt()];
                in.readFully(value);
                return value;
            }
            case 8: return in.readUTF();
            case 9: {
                NbtList list = new NbtList(in.readByte());
                int size = in.readInt();
                for (int i = 0; i < size; i++) {
                    Object element = readPayload(in, list.type, depth + 1);
                    // heterogeneous lists (1.21.5+) wrap their non-compound elements as {"": value}
                    Map<String, Object> wrapper = compound(element);
                    if (wrapper != null && wrapper.size() == 1 && wrapper.containsKey("")) element = wrapper.get("");
                    list.add(element);
                }
                return list;
            }
            case 10: {
                Map<String, Object> map = new LinkedHashMap<String, Object>();
                for (byte child = in.readByte(); child != 0; child = in.readByte()) {
                    String name = in.readUTF();
                    map.put(name, readPayload(in, child, depth + 1));
                }
                return map;
            }
            case 11: {
                int[] value = new int[in.readInt()];
                for (int i = 0; i < value.length; i++) value[i] = in.readInt();
                return value;
            }
            case 12: {
                long[] value = new long[in.readInt()];
                for (int i = 0; i < value.length; i++) value[i] = in.readLong();
                return value;
            }
            default: throw new IOException("Unknown NBT tag type " + type);
        }
    }

    private static byte[] writeRoot(Map<String, Object> root) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeByte(10);
            out.writeUTF("");
            writePayload(out, root);
        }
        return bytes.toByteArray();
    }

    private static byte type(Object tag) throws IOException {
        if (tag instanceof Byte) return 1;
        if (tag instanceof Short) return 2;
        if (tag instanceof Integer) return 3;
        if (tag instanceof Long) return 4;
        if (tag instanceof Float) return 5;
        if (tag instanceof Double) return 6;
        if (tag instanceof byte[]) return 7;
        if (tag instanceof String) return 8;
        if (tag instanceof NbtList) return 9;
        if (tag instanceof Map) return 10;
        if (tag instanceof int[]) return 11;
        if (tag instanceof long[]) return 12;
        throw new IOException("Not an NBT tag: " + tag);
    }

    private static void writePayload(DataOutputStream out, Object tag) throws IOException {
        switch (type(tag)) {
            case 1: out.writeByte((Byte) tag); break;
            case 2: out.writeShort((Short) tag); break;
            case 3: out.writeInt((Integer) tag); break;
            case 4: out.writeLong((Long) tag); break;
            case 5: out.writeFloat((Float) tag); break;
            case 6: out.writeDouble((Double) tag); break;
            case 7: {
                byte[] value = (byte[]) tag;
                out.writeInt(value.length);
                out.write(value);
                break;
            }
            case 8: out.writeUTF((String) tag); break;
            case 9: {
                NbtList list = (NbtList) tag;
                // a list of text components may now mix strings and compounds: wrap like Minecraft does
                byte elementType = list.isEmpty() ? list.type : type(list.get(0));
                boolean mixed = false;
                for (Object element : list) {
                    if (type(element) != elementType) {
                        mixed = true;
                        break;
                    }
                }
                if (mixed) elementType = 10;
                out.writeByte(elementType);
                out.writeInt(list.size());
                for (Object element : list) {
                    if (mixed && !(element instanceof Map)) {
                        // {"": value}, the wrapper of heterogeneous lists since 1.21.5
                        out.writeByte(type(element));
                        out.writeUTF("");
                        writePayload(out, element);
                        out.writeByte(0);
                    } else {
                        writePayload(out, element);
                    }
                }
                break;
            }
            case 10: {
                for (Map.Entry<String, Object> entry : compound(tag).entrySet()) {
                    out.writeByte(type(entry.getValue()));
                    out.writeUTF(entry.getKey());
                    writePayload(out, entry.getValue());
                }
                out.writeByte(0);
                break;
            }
            case 11: {
                int[] value = (int[]) tag;
                out.writeInt(value.length);
                for (int element : value) out.writeInt(element);
                break;
            }
            case 12: {
                long[] value = (long[]) tag;
                out.writeInt(value.length);
                for (long element : value) out.writeLong(element);
                break;
            }
            default: throw new IOException("Not an NBT tag: " + tag);
        }
    }
}
