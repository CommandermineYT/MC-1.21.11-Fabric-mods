package com.totalecollapse;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.Command;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class TotaleCollapse implements ModInitializer {
    public static final String MOD_ID = "totale-collapse";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int GROUPS_PER_STORM = 12;
    private static final int TICKS_BETWEEN_GROUPS = 8;

    private static final int MIN_CUBE_SIZE = 1;
    private static final int MAX_CUBE_SIZE = 4;

    private static final double MIN_SPAWN_HEIGHT = 30.0;
    private static final double MAX_SPAWN_HEIGHT = 60.0;

    private static final double MIN_TARGET_RADIUS = 18.0;
    private static final double MAX_TARGET_RADIUS = 70.0;

    private static final double MIN_ANGLE_DEGREES = 40.0;
    private static final double MAX_ANGLE_DEGREES = 50.0;
    private static final double SPEED = 0.75;

    private static final Random RANDOM = new Random();
    private static final List<MeteorGroup> ACTIVE_METEORS = new ArrayList<>();
    private static final List<MeteorStorm> ACTIVE_STORMS = new ArrayList<>();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                Commands.literal("collapse")
                    .then(
                        Commands.literal("meteor")
                            .executes(context -> {
                                ServerLevel level = context.getSource().getLevel();
                                Vec3 origin = context.getSource().getPosition();

                                ACTIVE_STORMS.add(
                                    new MeteorStorm(
                                        level,
                                        origin,
                                        GROUPS_PER_STORM,
                                        0
                                    )
                                );

                                context.getSource().sendSuccess(
                                    () -> Component.literal(
                                        "Total collapse initiated: 12 meteor groups incoming."
                                    ),
                                    true
                                );

                                return Command.SINGLE_SUCCESS;
                            })
                    )
            );
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> tickMeteors());
    }

    private static void tickMeteors() {
        tickStorms();
        tickMeteorGroups();
    }

    private static void tickStorms() {
        Iterator<MeteorStorm> iterator = ACTIVE_STORMS.iterator();

        while (iterator.hasNext()) {
            MeteorStorm storm = iterator.next();

            if (storm.ticksUntilNextGroup > 0) {
                storm.ticksUntilNextGroup--;
                continue;
            }

            spawnMeteorGroup(storm.level, storm.origin);

            storm.groupsRemaining--;
            storm.ticksUntilNextGroup = TICKS_BETWEEN_GROUPS;

            if (storm.groupsRemaining <= 0) {
                iterator.remove();
            }
        }
    }

    private static void spawnMeteorGroup(ServerLevel level, Vec3 origin) {
        int cubeSize = MIN_CUBE_SIZE + RANDOM.nextInt(MAX_CUBE_SIZE);

        double direction = RANDOM.nextDouble() * Math.PI * 2.0;
        double distance = MIN_TARGET_RADIUS
            + RANDOM.nextDouble() * (MAX_TARGET_RADIUS - MIN_TARGET_RADIUS);

        double targetX = origin.x + Math.cos(direction) * distance;
        double targetZ = origin.z + Math.sin(direction) * distance;

        double spawnHeight = MIN_SPAWN_HEIGHT
            + RANDOM.nextDouble() * (MAX_SPAWN_HEIGHT - MIN_SPAWN_HEIGHT);

        Vec3 groupCenter = new Vec3(
            origin.x,
            origin.y + spawnHeight,
            origin.z
        );

        Vec3 target = new Vec3(targetX, origin.y, targetZ);

        Vec3 horizontalDirection = new Vec3(
            target.x - groupCenter.x,
            0.0,
            target.z - groupCenter.z
        ).normalize();

        double angleRadians = Math.toRadians(
            MIN_ANGLE_DEGREES
                + RANDOM.nextDouble() * (MAX_ANGLE_DEGREES - MIN_ANGLE_DEGREES)
        );

        double horizontalSpeed = SPEED * Math.cos(angleRadians);
        double downwardSpeed = SPEED * Math.sin(angleRadians);

        Vec3 velocity = new Vec3(
            horizontalDirection.x * horizontalSpeed,
            -downwardSpeed,
            horizontalDirection.z * horizontalSpeed
        );

        List<FallingBlockEntity> blocks = new ArrayList<>();

        double centerOffset = (cubeSize - 1) / 2.0;

        for (int x = 0; x < cubeSize; x++) {
            for (int y = 0; y < cubeSize; y++) {
                for (int z = 0; z < cubeSize; z++) {
                    BlockPos spawnPos = BlockPos.containing(
                        groupCenter.x + x - centerOffset,
                        groupCenter.y + y - centerOffset,
                        groupCenter.z + z - centerOffset
                    );

                    FallingBlockEntity meteor = FallingBlockEntity.fall(
                        level,
                        spawnPos,
                        Blocks.MAGMA_BLOCK.defaultBlockState()
                    );

                    meteor.setDeltaMovement(velocity);
                    meteor.dropItem = false;

                    blocks.add(meteor);
                }
            }
        }

        ACTIVE_METEORS.add(
            new MeteorGroup(level, blocks, groupCenter)
        );
    }

    private static void tickMeteorGroups() {
        Iterator<MeteorGroup> iterator = ACTIVE_METEORS.iterator();

        while (iterator.hasNext()) {
            MeteorGroup group = iterator.next();
            Vec3 trailPosition = null;
            boolean anyBlockIsAlive = false;

            for (FallingBlockEntity meteor : group.blocks) {
                if (!meteor.isAlive()) {
                    continue;
                }

                anyBlockIsAlive = true;
                trailPosition = meteor.position();

                sendTrailParticles(group.level, trailPosition);
            }

            if (!anyBlockIsAlive) {
                spawnImpactBurst(group.level, group.lastKnownPosition);
                iterator.remove();
                continue;
            }

            if (trailPosition != null) {
                group.lastKnownPosition = trailPosition;
            }
        }
    }

    private static void sendTrailParticles(ServerLevel level, Vec3 position) {
        level.sendParticles(
            ParticleTypes.FLAME,
            position.x,
            position.y,
            position.z,
            6,
            0.35,
            0.35,
            0.35,
            0.02
        );

        level.sendParticles(
            ParticleTypes.LARGE_SMOKE,
            position.x,
            position.y,
            position.z,
            4,
            0.45,
            0.45,
            0.45,
            0.02
        );
    }

    private static void spawnImpactBurst(ServerLevel level, Vec3 position) {
        BlockParticleOption dustPillar = new BlockParticleOption(
            ParticleTypes.DUST_PILLAR,
            Blocks.MAGMA_BLOCK.defaultBlockState()
        );

        level.sendParticles(
            dustPillar,
            position.x,
            position.y,
            position.z,
            45,
            2.0,
            0.25,
            2.0,
            0.12
        );

        level.sendParticles(
            ParticleTypes.FLAME,
            position.x,
            position.y,
            position.z,
            40,
            1.4,
            0.5,
            1.4,
            0.14
        );

        level.sendParticles(
            ParticleTypes.LARGE_SMOKE,
            position.x,
            position.y,
            position.z,
            32,
            1.6,
            0.7,
            1.6,
            0.10
        );
    }

    private static final class MeteorGroup {
        private final ServerLevel level;
        private final List<FallingBlockEntity> blocks;
        private Vec3 lastKnownPosition;

        private MeteorGroup(
            ServerLevel level,
            List<FallingBlockEntity> blocks,
            Vec3 lastKnownPosition
        ) {
            this.level = level;
            this.blocks = blocks;
            this.lastKnownPosition = lastKnownPosition;
        }
    }

    private static final class MeteorStorm {
        private final ServerLevel level;
        private final Vec3 origin;
        private int groupsRemaining;
        private int ticksUntilNextGroup;

        private MeteorStorm(
            ServerLevel level,
            Vec3 origin,
            int groupsRemaining,
            int ticksUntilNextGroup
        ) {
            this.level = level;
            this.origin = origin;
            this.groupsRemaining = groupsRemaining;
            this.ticksUntilNextGroup = ticksUntilNextGroup;
        }
    }
}

