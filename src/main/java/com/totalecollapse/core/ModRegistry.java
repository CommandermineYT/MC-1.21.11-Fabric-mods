package com.totalecollapse.core;

import java.util.function.Function;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Central registration helpers for every piece of content in the mod.
 *
 * <p>In 1.21.11 both {@code Block} and {@code Item} must be told their own registry id
 * <em>before</em> construction, via {@code Properties.setId(ResourceKey)}. Forgetting that
 * throws at startup, so all registration funnels through here to make it impossible to skip.
 *
 * <p>{@code Properties} objects are mutable and {@code setId} mutates in place, which means a
 * {@code Properties} instance can never be shared between two blocks. Callers therefore pass a
 * factory that builds a fresh one per registration.
 */
public final class ModRegistry {

    public static final String MOD_ID = "totale-collapse";

    private ModRegistry() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    public static ResourceKey<Block> blockKey(String path) {
        return ResourceKey.create(Registries.BLOCK, id(path));
    }

    public static ResourceKey<Item> itemKey(String path) {
        return ResourceKey.create(Registries.ITEM, id(path));
    }

    /**
     * Registers a block and its matching {@link BlockItem}.
     *
     * @param path     registry path, e.g. {@code "arcanite_ore"}
     * @param settings a fresh Properties instance for this block alone
     * @param factory  builds the block from the id-stamped properties
     */
    public static Block registerBlockWithItem(String path,
                                              BlockBehaviour.Properties settings,
                                              Function<BlockBehaviour.Properties, Block> factory) {
        Block block = registerBlock(path, settings, factory);
        registerBlockItem(path, block);
        return block;
    }

    public static Block registerBlock(String path,
                                      BlockBehaviour.Properties settings,
                                      Function<BlockBehaviour.Properties, Block> factory) {
        ResourceKey<Block> key = blockKey(path);
        Block block = factory.apply(settings.setId(key));
        return Registry.register(BuiltInRegistries.BLOCK, key, block);
    }

    public static Item registerBlockItem(String path, Block block) {
        ResourceKey<Item> key = itemKey(path);
        Item item = new BlockItem(block, new Item.Properties()
                .useBlockDescriptionPrefix()
                .setId(key));

        return Registry.register(BuiltInRegistries.ITEM, key, item);
    }

    public static Item registerItem(String path) {
        return registerItem(path, new Item.Properties());
    }

    public static Item registerItem(String path, Item.Properties settings) {
        ResourceKey<Item> key = itemKey(path);
        return Registry.register(BuiltInRegistries.ITEM, key, new Item(settings.setId(key)));
    }
}
