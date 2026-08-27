package com.totalecollapse;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Stack Items — the stack-size half of Totale Collapse.
 *
 * <p>Vanilla will not let you go past 99 through the data component route: the
 * {@code minecraft:max_stack_size} component is validated against a hard 1..99
 * range, so writing 1000 into it is silently rejected. Instead this class holds
 * the policy and three tiny mixins ask it for an answer:
 *
 * <ul>
 *   <li>{@code ItemStack#getMaxStackSize()} — how many of this item may share a stack.</li>
 *   <li>{@code Slot#getMaxStackSize()} — the ceiling a GUI slot will accept.</li>
 *   <li>{@code Container#getMaxStackSize()} — the ceiling a container imposes.</li>
 * </ul>
 *
 * <p>Because all three of those are the single funnel every inventory, hopper,
 * shift-click and merge path in the game goes through, and because
 * {@code Container#getMaxStackSize()} is an interface default method, containers
 * added by other mods are picked up automatically without needing to know
 * anything about them. Mods that override those methods themselves keep their own
 * behaviour, which is the correct outcome.
 *
 * <p>Armour and offhand slots are deliberately left alone. Armour slots are
 * {@code Slot} subclasses that override {@code getMaxStackSize()} themselves, so
 * the mixin never fires for them; the offhand is a plain slot and is excluded by
 * index here.
 */
public final class StackItems {

    private static final Logger LOGGER = LoggerFactory.getLogger("totale-collapse/stack-items");

    /**
     * Absolute ceiling. The network codec for stack counts is a var-int so the
     * wire can carry far more than this, but inventory maths, container NBT and
     * the item count text in the HUD all get unpleasant well before a million.
     * A million is what was asked for, so a million is the cap.
     */
    public static final int HARD_CEILING = 1_000_000;

    private static final String CONFIG_FILE = "stackitems.properties";

    // ---- live configuration -------------------------------------------------
    private static boolean enabled = true;

    /** Applies to items vanilla stacks to 64. */
    private static int normalLimit = 1_000;

    /** Applies to items vanilla stacks to 16, such as snowballs and eggs. */
    private static int smallLimit = 1_000;

    /** Applies to items vanilla refuses to stack at all, such as tools. */
    private static int singleLimit = 1;

    /** Applies to anything carrying the equippable component. */
    private static int armourLimit = 16;

    /** Applies to anything carrying a non-empty enchantment list. */
    private static int enchantedLimit = 16;

    private static boolean stackArmour = true;
    private static boolean stackEnchanted = true;

    /**
     * Items that must never be raised. Anything holding other items inside it is
     * dangerous to stack: two shulker boxes merging would merge into one and take
     * their contents with them.
     */
    private static final Set<String> excluded = new HashSet<>();

    private static final String[] DEFAULT_EXCLUSIONS = {
        "minecraft:bundle",
        "minecraft:shulker_box",
        "minecraft:white_shulker_box",
        "minecraft:orange_shulker_box",
        "minecraft:magenta_shulker_box",
        "minecraft:light_blue_shulker_box",
        "minecraft:yellow_shulker_box",
        "minecraft:lime_shulker_box",
        "minecraft:pink_shulker_box",
        "minecraft:gray_shulker_box",
        "minecraft:light_gray_shulker_box",
        "minecraft:cyan_shulker_box",
        "minecraft:purple_shulker_box",
        "minecraft:blue_shulker_box",
        "minecraft:brown_shulker_box",
        "minecraft:green_shulker_box",
        "minecraft:red_shulker_box",
        "minecraft:black_shulker_box",
        "minecraft:written_book",
        "minecraft:filled_map"
    };

    // ---- per-mod overrides --------------------------------------------------
    /**
     * Namespace to stack limit. Checked before the generic rules, so a mod can be
     * given its own ceiling — or opted out entirely with a limit of 0, which means
     * "leave every item from this mod exactly as its author intended".
     *
     * <p>Ordered so the config file it writes stays stable between launches.
     */
    private static final Map<String, Integer> MOD_LIMITS = new LinkedHashMap<>();

    /**
     * Placeholder table for mods that carry their own inventories around. All of
     * these default to 0, meaning untouched, because a backpack that merges with
     * another backpack eats its own contents. Uncomment a value in the config
     * file to opt one back in once you have tested it.
     *
     * <p>Add new mods here, or at runtime with
     * {@link #setModLimit(String, int)} from another mod's initialiser.
     */
    private static final String[][] MOD_PLACEHOLDERS = {
        {"adventurersbackpack", "0", "Adventurer's Backpack — wearable container, do not stack"},
        {"sophisticatedbackpacks", "0", "Sophisticated Backpacks — wearable container"},
        {"sophisticatedstorage", "0", "Sophisticated Storage — barrels and chests hold items"},
        {"travelersbackpack", "0", "Traveler's Backpack — wearable container"},
        {"ironchests", "0", "Iron Chests — block items are fine, contents are not"},
        {"expandedstorage", "0", "Expanded Storage"},
        {"toms_storage", "0", "Tom's Simple Storage"},
        {"functionalstorage", "0", "Functional Storage"},
        {"ae2", "0", "Applied Energistics 2 — cells hold items"},
        {"refinedstorage", "0", "Refined Storage — disks hold items"},
        {"create", "0", "Create — many items are stack-size sensitive in its logistics"},
        {"farmersdelight", "-1", "Farmer's Delight — food, safe to raise"},
        {"croptopia", "-1", "Croptopia — food, safe to raise"},
        {"supplementaries", "-1", "Supplementaries"},
        {"twilightforest", "-1", "The Twilight Forest"},
        {"mekanism", "0", "Mekanism — QIO and cardboard boxes hold items"},
        {"thermal", "-1", "Thermal series"},
        {"immersiveengineering", "-1", "Immersive Engineering"},
        {"botania", "0", "Botania — several items rely on stack size of one"},
        {"occultism", "0", "Occultism — storage actuators hold items"}
    };

    private StackItems() {
    }

    // ==================================================================
    // Lifecycle
    // ==================================================================
    /** Call once from the mod initialiser, before anything touches an inventory. */
    public static void load() {
        excluded.clear();
        excluded.addAll(Arrays.asList(DEFAULT_EXCLUSIONS));

        MOD_LIMITS.clear();

        for (String[] placeholder : MOD_PLACEHOLDERS) {
            MOD_LIMITS.put(placeholder[0], Integer.valueOf(placeholder[1]));
        }

        Path configPath = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE);

        Properties properties = new Properties();

        if (Files.exists(configPath)) {
            try (InputStream in = Files.newInputStream(configPath)) {
                properties.load(in);
            } catch (IOException failure) {
                LOGGER.warn("Could not read {}, using defaults", CONFIG_FILE, failure);
            }

            apply(properties);
        }

        try {
            write(configPath);
        } catch (IOException failure) {
            LOGGER.warn("Could not write {}", CONFIG_FILE, failure);
        }

        LOGGER.info(
                "Stack Items ready: enabled={} normal={} small={} single={} armour={} enchanted={}",
                enabled, normalLimit, smallLimit, singleLimit,
                stackArmour ? armourLimit : "off",
                stackEnchanted ? enchantedLimit : "off");
    }

    private static void apply(Properties properties) {
        enabled = readBoolean(properties, "enabled", enabled);

        normalLimit = readLimit(properties, "limit.normal", normalLimit);
        smallLimit = readLimit(properties, "limit.small", smallLimit);
        singleLimit = readLimit(properties, "limit.unstackable", singleLimit);
        armourLimit = readLimit(properties, "limit.armour", armourLimit);
        enchantedLimit = readLimit(properties, "limit.enchanted", enchantedLimit);

        stackArmour = readBoolean(properties, "stackArmour", stackArmour);
        stackEnchanted = readBoolean(properties, "stackEnchanted", stackEnchanted);

        String exclusions = properties.getProperty("excludedItems");

        if (exclusions != null && !exclusions.isBlank()) {
            excluded.clear();

            for (String id : exclusions.split(",")) {
                String trimmed = id.trim().toLowerCase(Locale.ROOT);

                if (!trimmed.isEmpty()) {
                    excluded.add(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
                }
            }
        }

        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith("mod.")) {
                continue;
            }

            String modId = name.substring(4);

            try {
                MOD_LIMITS.put(modId, clamp(Integer.parseInt(properties.getProperty(name).trim())));
            } catch (NumberFormatException ignored) {
                LOGGER.warn("Ignoring unreadable value for {}", name);
            }
        }
    }

    private static void write(Path configPath) throws IOException {
        Properties properties = new Properties();

        properties.setProperty("enabled", Boolean.toString(enabled));
        properties.setProperty("limit.normal", Integer.toString(normalLimit));
        properties.setProperty("limit.small", Integer.toString(smallLimit));
        properties.setProperty("limit.unstackable", Integer.toString(singleLimit));
        properties.setProperty("limit.armour", Integer.toString(armourLimit));
        properties.setProperty("limit.enchanted", Integer.toString(enchantedLimit));
        properties.setProperty("stackArmour", Boolean.toString(stackArmour));
        properties.setProperty("stackEnchanted", Boolean.toString(stackEnchanted));
        properties.setProperty("excludedItems", String.join(",", excluded));

        for (Map.Entry<String, Integer> entry : MOD_LIMITS.entrySet()) {
            properties.setProperty("mod." + entry.getKey(), Integer.toString(entry.getValue()));
        }

        Files.createDirectories(configPath.getParent());

        try (OutputStream out = Files.newOutputStream(configPath)) {
            properties.store(out, """
                    Stack Items, part of Totale Collapse.
                    Limits are per inventory slot and cap at 1000000.
                    limit.normal      items vanilla stacks to 64
                    limit.small       items vanilla stacks to 16
                    limit.unstackable items vanilla will not stack at all
                    limit.armour      anything equippable, needs stackArmour=true
                    limit.enchanted   anything enchanted, needs stackEnchanted=true
                    mod.<namespace>   per mod override. 0 leaves that mod untouched,
                                      -1 means follow the generic rules above.
                    Armour slots and the offhand slot always hold one item.""");
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        String raw = properties.getProperty(key);

        return raw == null ? fallback : Boolean.parseBoolean(raw.trim());
    }

    private static int readLimit(Properties properties, String key, int fallback) {
        String raw = properties.getProperty(key);

        if (raw == null) {
            return fallback;
        }

        try {
            return clamp(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException ignored) {
            LOGGER.warn("Ignoring unreadable value for {}", key);
            return fallback;
        }
    }

    private static int clamp(int value) {
        return Math.max(-1, Math.min(HARD_CEILING, value));
    }

    /**
     * Registers or replaces a per-mod ceiling at runtime. Pass 0 to leave a mod
     * alone entirely, or -1 to let it follow the generic rules. Intended as the
     * hook another mod, or a future addon file, uses instead of editing this
     * class.
     */
    public static void setModLimit(String modId, int limit) {
        MOD_LIMITS.put(modId.toLowerCase(Locale.ROOT), clamp(limit));
    }

    /** Adds an item id that must keep its vanilla stack size. */
    public static void exclude(String itemId) {
        excluded.add(itemId.toLowerCase(Locale.ROOT));
    }

    // ==================================================================
    // Policy, called from the mixins
    // ==================================================================
    /**
     * Answers how large a stack of this item may be.
     *
     * @param stack   the stack being asked about
     * @param vanilla whatever the game was about to return
     * @return the replacement ceiling, or {@code vanilla} to change nothing
     */
    public static int limitFor(ItemStack stack, int vanilla) {
        if (!enabled || stack.isEmpty()) {
            return vanilla;
        }

        // Anything that carries items inside it merges destructively.
        if (stack.has(DataComponents.CONTAINER) || stack.has(DataComponents.BUNDLE_CONTENTS)) {
            return vanilla;
        }

        // 1.21.11 renamed ResourceLocation to Identifier. The namespace/path
        // string is built by hand rather than leaning on toString(), so a future
        // change to that method cannot quietly break the exclusion list.
        Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());

        if (id != null) {
            if (excluded.contains(id.getNamespace() + ":" + id.getPath())) {
                return vanilla;
            }

            Integer modLimit = MOD_LIMITS.get(id.getNamespace());

            if (modLimit != null) {
                if (modLimit == 0) {
                    return vanilla;
                }

                if (modLimit > 0) {
                    return modLimit;
                }

                // -1 falls through to the generic rules below.
            }
        }

        if (stack.has(DataComponents.EQUIPPABLE)) {
            return stackArmour ? armourLimit : vanilla;
        }

        if (stack.has(DataComponents.ENCHANTMENTS)
                && !stack.getEnchantments().isEmpty()) {
            return stackEnchanted ? enchantedLimit : vanilla;
        }

        if (vanilla <= 1) {
            return singleLimit;
        }

        if (vanilla <= 16) {
            return smallLimit;
        }

        return normalLimit;
    }

    /**
     * The ceiling a plain GUI slot or container should allow. Kept separate from
     * {@link #limitFor} because a slot does not always know which item it is
     * being asked about, and it must never be the thing that lowers a limit.
     */
    public static int slotLimit(int vanilla) {
        if (!enabled) {
            return vanilla;
        }

        return Math.max(vanilla, Math.max(normalLimit, Math.max(smallLimit, armourLimit)));
    }

    /**
     * True for slots that must keep holding exactly one item. Armour slots
     * override {@code getMaxStackSize} themselves so the mixin never sees them;
     * the offhand does not, so it is named here.
     */
    public static boolean isRestrictedSlot(Container container, int index) {
        if (!(container instanceof Inventory)) {
            return false;
        }

        return index == Inventory.SLOT_OFFHAND
                || index == Inventory.SLOT_BODY_ARMOR
                || index == Inventory.SLOT_SADDLE;
    }

    /** Exposed so other code can ask without reaching into the fields. */
    public static boolean isEnabled() {
        return enabled;
    }

    /** Snapshot of the per-mod table, for debug commands. */
    public static Map<String, Integer> modLimits() {
        return new HashMap<>(MOD_LIMITS);
    }
}
