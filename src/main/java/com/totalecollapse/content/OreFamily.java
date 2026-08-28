package com.totalecollapse.content;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.totalecollapse.core.ModRegistry;

import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * One complete ore chain: stone ore, deepslate ore, raw drop, refined ingot, storage block, and a
 * full five-piece tool set.
 *
 * <p>Constructing an instance registers everything immediately, so instances must only be created
 * during mod initialisation. Registration order here also fixes creative-tab order.
 *
 * <p>Tools are brand new registry entries with their own sprites -- nothing here overrides or
 * retextures a vanilla item.
 */
public final class OreFamily {

    /** Base name, e.g. {@code "mithril"}. Also the worldgen feature suffix. */
    public final String name;

    /** Registry path of the refined ingot, e.g. {@code "mithril_ingot"}. */
    public final String ingotName;

    public final Block stoneOre;
    public final Block deepslateOre;
    public final Block storageBlock;

    public final Item rawItem;
    public final Item ingotItem;

    /** Tool kind ("pickaxe", "axe", "shovel", "hoe", "sword") to registered item. */
    public final Map<String, Item> tools = new LinkedHashMap<>();

    public OreFamily(String name,
                     MapColor mapColor,
                     float oreHardness,
                     int minExperience,
                     int maxExperience,
                     ToolMaterial material) {

        this.name = name;
        this.ingotName = name + "_ingot";

        UniformInt experience = UniformInt.of(minExperience, maxExperience);

        this.stoneOre = ModRegistry.registerBlockWithItem(
                name + "_ore",
                oreSettings(mapColor, oreHardness),
                settings -> new DropExperienceBlock(experience, settings));

        // Deepslate variants are harder than their stone counterparts in vanilla, so match that.
        this.deepslateOre = ModRegistry.registerBlockWithItem(
                "deepslate_" + name + "_ore",
                oreSettings(MapColor.DEEPSLATE, oreHardness + 1.5F).sound(SoundType.DEEPSLATE),
                settings -> new DropExperienceBlock(experience, settings));

        this.storageBlock = ModRegistry.registerBlockWithItem(
                name + "_block",
                BlockBehaviour.Properties.of()
                        .mapColor(mapColor)
                        .strength(5.0F, 6.0F)
                        .requiresCorrectToolForDrops()
                        .sound(SoundType.METAL),
                Block::new);

        this.rawItem = ModRegistry.registerItem("raw_" + name);
        this.ingotItem = ModRegistry.registerItem(ingotName);

        // Attack damage and speed values mirror the vanilla curve for each tool shape.
        tools.put("pickaxe", ModRegistry.registerItem(name + "_pickaxe",
                new Item.Properties().pickaxe(material, 1.0F, -2.8F)));
        tools.put("axe", ModRegistry.registerItem(name + "_axe",
                new Item.Properties().axe(material, 6.0F, -3.1F)));
        tools.put("shovel", ModRegistry.registerItem(name + "_shovel",
                new Item.Properties().shovel(material, 1.5F, -3.0F)));
        tools.put("hoe", ModRegistry.registerItem(name + "_hoe",
                new Item.Properties().hoe(material, 0.0F, -1.0F)));
        tools.put("sword", ModRegistry.registerItem(name + "_sword",
                new Item.Properties().sword(material, 3.0F, -2.4F)));
    }

    private static BlockBehaviour.Properties oreSettings(MapColor mapColor, float hardness) {
        return BlockBehaviour.Properties.of()
                .mapColor(mapColor)
                .strength(hardness, hardness + 2.0F)
                .requiresCorrectToolForDrops()
                .sound(SoundType.STONE);
    }

    /** Blocks and raw/refined materials, in creative-tab order. */
    public List<Item> materialItems() {
        List<Item> items = new ArrayList<>();

        items.add(stoneOre.asItem());
        items.add(deepslateOre.asItem());
        items.add(rawItem);
        items.add(ingotItem);
        items.add(storageBlock.asItem());

        return items;
    }

    /** The five tools, in creative-tab order. */
    public List<Item> toolItems() {
        return new ArrayList<>(tools.values());
    }
}
