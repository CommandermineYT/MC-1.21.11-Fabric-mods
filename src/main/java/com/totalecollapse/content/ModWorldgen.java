package com.totalecollapse.content;

import com.totalecollapse.core.ModRegistry;

import net.fabricmc.fabric.api.biome.v1.BiomeModifications;
import net.fabricmc.fabric.api.biome.v1.BiomeSelectors;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

/**
 * Injects the ore placed features into overworld biomes.
 *
 * <p>The features themselves are declared as JSON under
 * {@code data/totale-collapse/worldgen/}, because worldgen is data-driven and JSON stays
 * readable and pack-overridable. This class only says <em>where</em> they apply.
 */
public final class ModWorldgen {

    private ModWorldgen() {
    }

    public static void init() {
        for (OreFamily family : ModOres.ALL) {
            ResourceKey<PlacedFeature> feature = ResourceKey.create(
                    Registries.PLACED_FEATURE,
                    ModRegistry.id("ore_" + family.name));

            BiomeModifications.addFeature(
                    BiomeSelectors.foundInOverworld(),
                    GenerationStep.Decoration.UNDERGROUND_ORES,
                    feature);
        }
    }
}
