package com.totalecollapse.content;

import java.util.List;

import net.minecraft.world.level.material.MapColor;

/**
 * The mystical ores. These are the mod's own materials -- they do not replace or retexture any
 * vanilla ore, and each one carries a full tool tier.
 *
 * <p>Depth and spawn rate live in the placed-feature JSON under
 * {@code data/totale-collapse/worldgen/placed_feature/}; hardness, drops and experience live here;
 * tool stats live in {@link ModToolMaterials}.
 */
public final class ModOres {

    /** Light silvery-blue. Shallow and fairly common, the first mystical tier. */
    public static final OreFamily MITHRIL = new OreFamily(
            "mithril", MapColor.COLOR_LIGHT_BLUE, 3.0F, 0, 2, ModToolMaterials.MITHRIL);

    /** Warm antique gold. Mid depth, tougher than Mithril and highly enchantable. */
    public static final OreFamily ORICHALCUM = new OreFamily(
            "orichalcum", MapColor.GOLD, 3.5F, 1, 3, ModToolMaterials.ORICHALCUM);

    /** Deep crimson. Rare and deep, diamond mining level. */
    public static final OreFamily ADAMANTITE = new OreFamily(
            "adamantite", MapColor.COLOR_RED, 4.5F, 2, 5, ModToolMaterials.ADAMANTITE);

    /** Pale violet starlight. Deepslate depths only, the end-game tier. */
    public static final OreFamily AETHERIUM = new OreFamily(
            "aetherium", MapColor.COLOR_PURPLE, 5.0F, 4, 9, ModToolMaterials.AETHERIUM);

    public static final List<OreFamily> ALL = List.of(MITHRIL, ORICHALCUM, ADAMANTITE, AETHERIUM);

    private ModOres() {
    }

    /**
     * Forces class loading, which runs the static initialisers above and therefore performs all
     * registration. Call this from {@code onInitialize()} before anything touches the ores.
     */
    public static void init() {
        // Intentionally empty: the static fields above do the work.
    }
}
