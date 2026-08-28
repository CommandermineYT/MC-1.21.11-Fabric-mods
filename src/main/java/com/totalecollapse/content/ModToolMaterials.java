package com.totalecollapse.content;

import com.totalecollapse.core.ModRegistry;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;

/**
 * Tool tiers for the mystical ores.
 *
 * <p>{@link ToolMaterial} is a record in 1.21.11, so a tier is just data:
 * {@code (incorrectBlocksForDrops, durability, speed, attackDamageBonus, enchantmentValue,
 * repairItems)}. The repair tag for each tier is generated as
 * {@code data/totale-collapse/tags/item/repairs_<ore>_tool.json} and contains that ore's ingot.
 *
 * <p>Tiers are deliberately spread across the vanilla curve rather than all sitting above
 * netherite: Mithril and Orichalcum slot in around iron/diamond, Adamantite sits at diamond
 * mining level with much better durability, and Aetherium is the only true end-game tier.
 */
public final class ModToolMaterials {

    private ModToolMaterials() {
    }

    private static TagKey<Item> repairTag(String ore) {
        return TagKey.create(Registries.ITEM, ModRegistry.id("repairs_" + ore + "_tool"));
    }

    /** Light and fast, but not especially durable. Mines what iron mines. */
    public static final ToolMaterial MITHRIL = new ToolMaterial(
            BlockTags.INCORRECT_FOR_IRON_TOOL, 850, 7.0F, 2.5F, 18, repairTag("mithril"));

    /** Slower to gather but tougher, and takes enchantments unusually well. */
    public static final ToolMaterial ORICHALCUM = new ToolMaterial(
            BlockTags.INCORRECT_FOR_IRON_TOOL, 1100, 8.0F, 3.0F, 22, repairTag("orichalcum"));

    /** Diamond mining level with far more durability, and the best raw damage below Aetherium. */
    public static final ToolMaterial ADAMANTITE = new ToolMaterial(
            BlockTags.INCORRECT_FOR_DIAMOND_TOOL, 1800, 9.0F, 4.0F, 14, repairTag("adamantite"));

    /** End-game tier: mines everything netherite can, with the deepest durability pool. */
    public static final ToolMaterial AETHERIUM = new ToolMaterial(
            BlockTags.INCORRECT_FOR_NETHERITE_TOOL, 2600, 10.0F, 5.0F, 26, repairTag("aetherium"));
}
