package com.totalecollapse.content;

import com.totalecollapse.core.ModRegistry;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Two creative inventory tabs: raw materials, and everything you swing. */
public final class ModItemGroups {

    public static final ResourceKey<CreativeModeTab> MATERIALS_KEY =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, ModRegistry.id("ores"));

    public static final ResourceKey<CreativeModeTab> TOOLS_KEY =
            ResourceKey.create(Registries.CREATIVE_MODE_TAB, ModRegistry.id("tools"));

    private ModItemGroups() {
    }

    public static void init() {
        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, MATERIALS_KEY,
                FabricItemGroup.builder()
                        .icon(() -> new ItemStack(ModOres.AETHERIUM.ingotItem))
                        .title(Component.translatable("itemGroup.totale-collapse.ores"))
                        .displayItems((parameters, output) -> {
                            for (OreFamily family : ModOres.ALL) {
                                for (Item item : family.materialItems()) {
                                    output.accept(item);
                                }
                            }
                        })
                        .build());

        Registry.register(BuiltInRegistries.CREATIVE_MODE_TAB, TOOLS_KEY,
                FabricItemGroup.builder()
                        .icon(() -> new ItemStack(ModTools.HAMMER))
                        .title(Component.translatable("itemGroup.totale-collapse.tools"))
                        .displayItems((parameters, output) -> {
                            for (Item item : ModTools.ALL) {
                                output.accept(item);
                            }
                            for (OreFamily family : ModOres.ALL) {
                                for (Item item : family.toolItems()) {
                                    output.accept(item);
                                }
                            }
                        })
                        .build());
    }
}
