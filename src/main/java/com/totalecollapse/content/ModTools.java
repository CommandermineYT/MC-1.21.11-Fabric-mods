package com.totalecollapse.content;

import java.util.List;

import com.totalecollapse.core.ModRegistry;

import net.minecraft.world.item.Item;

/**
 * The special tools that replace typing commands with holding an object.
 *
 * <p>Each one is a new registry entry with its own sprite. In particular the Hammer exists so the
 * world-editing selection tool is no longer a vanilla golden axe -- see
 * {@code WorldEditor.WAND_ITEM}.
 */
public final class ModTools {

    /** World-edit selection tool. Left-click sets corner 1, right-click sets corner 2. */
    public static final Item HAMMER = ModRegistry.registerItem("hammer",
            new Item.Properties().stacksTo(1).durability(0));

    /** Right-click a block to call a meteor group down onto it. */
    public static final Item METEOR_STAFF = ModRegistry.registerItem("meteor_staff",
            new Item.Properties().stacksTo(1).durability(0));

    /** Right-click, then click a creature to possess it. */
    public static final Item MIND_SHARD = ModRegistry.registerItem("mind_shard",
            new Item.Properties().stacksTo(1).durability(0));

    public static final List<Item> ALL = List.of(HAMMER, METEOR_STAFF, MIND_SHARD);

    private ModTools() {
    }

    public static void init() {
        // Intentionally empty: the static fields above do the work.
    }
}
