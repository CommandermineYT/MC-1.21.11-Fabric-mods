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
            spawnShockwaveRing(level, origin, 0.7, 0.6, 42, 0.35, ParticleTypes.CLOUD);
            spawnShockwaveRing(level, origin, 2.0, 0.7, 56, 0.15, ParticleTypes.POOF);
            return;
        }

        for (int i = 0; i < sourceBlocks.size(); i++) {
            BlockPos source = sourceBlocks.get(i);
            BlockState state = level.getBlockState(source);
            if (state.isAir() || state.is(Blocks.BEDROCK)) {
                continue;
            }

            BlockPos target = findPullTarget(level, source, origin);
            if (target == null || target.equals(source)) {
                continue;
            }

            level.setBlock(target, state, Block.UPDATE_CLIENTS);
            level.setBlock(source, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);

            level.sendParticles(
                ParticleTypes.CLOUD,
                source.getX() + 0.5,
                source.getY() + 0.5,
                source.getZ() + 0.5,
                4,
                0.12,
                0.12,
                0.12,
                0.0
            );
        }

        spawnShockwaveRing(level, origin, 0.8, 0.6, 48, 0.35, ParticleTypes.CLOUD);
        spawnShockwaveRing(level, origin, 2.2, 0.8, 64, 0.2, ParticleTypes.POOF);
        spawnShockwaveRing(level, origin, 4.0, 1.0, 72, 0.0, ParticleTypes.SMOKE);
    }

    private static BlockPos findPullTarget(ServerLevel level, BlockPos source, Vec3 origin) {
        Vec3 sourceCenter = Vec3.atCenterOf(source);
        Vec3 offset = origin.subtract(sourceCenter);
        double length = offset.length();
        if (length < 0.001) {
            return null;
        }

        Vec3 direction = offset.normalize();
        for (int step = 1; step <= 4; step++) {
            double distance = 0.8 + step * 0.75;
            BlockPos candidate = BlockPos.containing(sourceCenter.add(direction.scale(distance)));
            if (candidate.equals(source)) {
                continue;
            }

            BlockState candidateState = level.getBlockState(candidate);
            if (candidateState.isAir()) {
                return candidate;
            }
        }

        return null;
    }

    private static void spawnShockwaveRing(ServerLevel level, Vec3 origin, double startRadius, double step, int points, double heightOffset, net.minecraft.core.particles.ParticleOptions particle) {
        for (int i = 0; i < points; i++) {
            double angle = (Math.PI * 2.0 * i) / points;
            double radius = startRadius + (i % 9) * step;
            double x = origin.x + Math.cos(angle) * radius;
            double y = origin.y + heightOffset + (i % 3) * 0.35;
            double z = origin.z + Math.sin(angle) * radius;

            level.sendParticles(
                particle,
                x,
                y,
                z,
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
