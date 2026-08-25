package com.totalecollapse;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class LightningStrike {
    private LightningStrike() {
    }

    public static void triggerLightning(ServerLevel level, Vec3 origin) {
        BlockPos pos = findGroundPos(level, origin);
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(
            level,
            null,
            pos,
            EntitySpawnReason.COMMAND,
            true,
            false
        );

        if (bolt != null) {
            level.addFreshEntity(bolt);
        }
    }

    private static BlockPos findGroundPos(ServerLevel level, Vec3 origin) {
        int minY = level.getMinY();
        int startY = Math.max(minY, (int) Math.floor(origin.y));
        for (int y = startY; y >= minY; y--) {
            BlockPos pos = BlockPos.containing(origin.x, y, origin.z);
            if (!level.getBlockState(pos).isAir() && !level.getBlockState(pos).is(Blocks.BEDROCK)) {
                return pos.above();
            }
        }
        return BlockPos.containing(origin.x, origin.y, origin.z);
    }
}
