package com.totalecollapse;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class EarthWave {
    private EarthWave() {
    }

    public static void triggerEarthWave(ServerLevel level, Vec3 origin) {
        List<BlockPos> sourceBlocks = collectNearbyBlocks(level, origin, 4, 8);
        if (sourceBlocks.isEmpty()) {
            level.sendParticles(
                ParticleTypes.EXPLOSION,
                origin.x,
                origin.y,
                origin.z,
                12,
                1.2,
                0.5,
                1.2,
                0.05
            );
            return;
        }

        for (int i = 0; i < sourceBlocks.size(); i++) {
            BlockPos source = sourceBlocks.get(i);
            BlockState state = level.getBlockState(source);
            if (state.isAir() || state.is(Blocks.BEDROCK)) {
                continue;
            }

            double angle = (Math.PI * 2.0 * i) / Math.max(1, sourceBlocks.size());
            double ringRadius = 2.3 + ((i % 6) * 0.65);
            double targetX = origin.x + Math.cos(angle) * ringRadius;
            double targetZ = origin.z + Math.sin(angle) * ringRadius;
            int targetY = source.getY() + ((i % 3) - 1);
            BlockPos target = BlockPos.containing(targetX, targetY, targetZ);

            if (level.isEmptyBlock(target) || level.getBlockState(target).canOcclude()) {
                level.setBlock(target, state, Block.UPDATE_CLIENTS);
                level.setBlock(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }

            level.sendParticles(
                ParticleTypes.EXPLOSION,
                source.getX() + 0.5,
                source.getY() + 0.5,
                source.getZ() + 0.5,
                4,
                0.15,
                0.15,
                0.15,
                0.0
            );
        }

        for (int i = 0; i < 30; i++) {
            double angle = (Math.PI * 2.0 * i) / 30.0;
            double radius = 1.5 + (i % 8) * 0.6;
            double px = origin.x + Math.cos(angle) * radius;
            double py = origin.y + 0.5 + (i % 3);
            double pz = origin.z + Math.sin(angle) * radius;

            level.sendParticles(
                ParticleTypes.CLOUD,
                px,
                py,
                pz,
                1,
                0.0,
                0.0,
                0.0,
                0.0
            );
        }
    }

    private static List<BlockPos> collectNearbyBlocks(ServerLevel level, Vec3 origin, int radius, int maxBlocks) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos center = BlockPos.containing(origin);

        for (int x = -radius; x <= radius; x++) {
            for (int y = -2; y <= 3; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = center.offset(x, y, z);
                    BlockState state = level.getBlockState(pos);

                    if (!state.isAir() && !state.is(Blocks.BEDROCK)) {
                        found.add(pos);
                        if (found.size() >= maxBlocks) {
                            return found;
                        }
                    }
                }
            }
        }

        return found;
    }
}
