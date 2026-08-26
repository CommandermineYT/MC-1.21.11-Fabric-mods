package com.totalecollapse;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
public class TotaleCollapse implements ModInitializer {

    public static final String MOD_ID = "totale-collapse";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final int GROUPS_PER_STORM = 12;
    private static final int TICKS_BETWEEN_GROUPS = 8;

    private static final int[] METEOR_SIZES = {3, 5, 9};

    private static final double MIN_SPAWN_HEIGHT = 30.0;
    private static final double MAX_SPAWN_HEIGHT = 60.0;

    private static final double MIN_TARGET_RADIUS = 25.0;
    private static final double MAX_TARGET_RADIUS = 150.0;

    private static final double MIN_ANGLE_DEGREES = 35.0;
    private static final double MAX_ANGLE_DEGREES = 65.0;
    private static final double SPEED = 0.75;

    private static final Random RANDOM = new Random();
    private static final List<MeteorGroup> ACTIVE_METEORS = new ArrayList<>();
    private static final List<MeteorStorm> ACTIVE_STORMS = new ArrayList<>();

    @Override
    public void onInitialize() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (world.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            if (!(player instanceof ServerPlayer serverPlayer) || !MindControlManager.isAwaitingTarget(serverPlayer)) {
                return InteractionResult.PASS;
            }

            MindControlManager.tryPossess(serverPlayer, entity);
            return InteractionResult.SUCCESS;
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) ->
                !(MindControlManager.isPossessedEntity(entity) && source.getEntity() instanceof Enemy));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server)
                -> MindControlManager.handleDisconnect(handler.getPlayer()));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    Commands.literal("collapse")
                            .then(
                                    Commands.literal("meteor_shower")
                                            .then(
                                                    Commands.argument("x", DoubleArgumentType.doubleArg())
                                                            .then(
                                                                    Commands.argument("y", DoubleArgumentType.doubleArg())
                                                                            .then(
                                                                                    Commands.argument("z", DoubleArgumentType.doubleArg())
                                                                                            .executes(context -> {
                                                                                                ServerLevel level = context.getSource().getLevel();

                                                                                                double x = DoubleArgumentType.getDouble(context, "x");
                                                                                                double y = DoubleArgumentType.getDouble(context, "y");
                                                                                                double z = DoubleArgumentType.getDouble(context, "z");

                                                                                                Vec3 impactOrigin = new Vec3(x, y, z);

                                                                                                ACTIVE_STORMS.add(
                                                                                                        new MeteorStorm(
                                                                                                                level,
                                                                                                                impactOrigin,
                                                                                                                GROUPS_PER_STORM,
                                                                                                                0
                                                                                                        )
                                                                                                );

                                                                                                LOGGER.info("Meteor shower targeting {},{},{}", x, y, z);

                                                                                                return Command.SINGLE_SUCCESS;
                                                                                            })
                                                                            )
                                                            )
                                            )
                            )
                            .then(
                                    Commands.literal("MindControll")
                                            .executes(context -> {
                                                MindControlManager.beginAwaitingTarget(context.getSource().getPlayerOrException());
                                                return Command.SINGLE_SUCCESS;
                                            })
                                            .then(
                                                    Commands.literal("Stop")
                                                            .executes(context -> {
                                                                MindControlManager.stop(context.getSource().getPlayerOrException());
                                                                return Command.SINGLE_SUCCESS;
                                                            })
                                            )
                            )
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

                                                LOGGER.info("Total collapse initiated: 12 meteor groups incoming at {}", origin);

                                                return Command.SINGLE_SUCCESS;
                                            })
                            )
            );
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickMeteors();
            MindControlManager.tick();
        });
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
        int meteorSize = METEOR_SIZES[RANDOM.nextInt(METEOR_SIZES.length)];
        double radius = meteorSize / 2.0;

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

        int center = meteorSize / 2;

        for (int dx = 0; dx < meteorSize; dx++) {
            for (int dy = 0; dy < meteorSize; dy++) {
                for (int dz = 0; dz < meteorSize; dz++) {
                    double offsetX = dx - center;
                    double offsetY = dy - center;
                    double offsetZ = dz - center;

                    double distanceSquared = offsetX * offsetX + offsetY * offsetY + offsetZ * offsetZ;
                    double radiusSquared = radius * radius;

                    if (distanceSquared > radiusSquared) {
                        continue;
                    }

                    if (distanceSquared > 1.0 && RANDOM.nextDouble() < 0.12) {
                        continue;
                    }

                    BlockPos spawnPos = BlockPos.containing(
                            groupCenter.x + offsetX,
                            groupCenter.y + offsetY,
                            groupCenter.z + offsetZ
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

        ACTIVE_METEORS.add(new MeteorGroup(level, blocks, groupCenter, meteorSize));
    }

    private static final int MAX_GROUP_LIFETIME_TICKS = 600;

private static void tickMeteorGroups() {
    Iterator<MeteorGroup> iterator = ACTIVE_METEORS.iterator();

    while (iterator.hasNext()) {
        MeteorGroup group = iterator.next();

        // Undo anything vanilla placed during its own entity tick.
        for (FallingBlockEntity meteor : group.blocks) {
            clearMeteorBlock(group.level, meteor);
        }

        group.age++;

        Vec3 sum = Vec3.ZERO;
        int alive = 0;
        Vec3 contact = null;

        for (FallingBlockEntity meteor : group.blocks) {
            if (!meteor.isAlive()) {
                continue;
            }

            Vec3 pos = meteor.position();
            sum = sum.add(pos);
            alive++;

            if (contact == null && willHitSomething(group.level, meteor)) {
                contact = pos;
            }
        }

        if (alive > 0) {
            group.lastKnownPosition = sum.scale(1.0 / alive);
            sendGroupTrail(group, alive);
        }

        boolean lostToVoid = group.lastKnownPosition.y < group.level.getMinY() + 2;

        if (contact != null || alive == 0 || lostToVoid || group.age > MAX_GROUP_LIFETIME_TICKS) {
            Vec3 impact = contact != null ? contact : group.lastKnownPosition;

            discardRemainingBlocks(group);

            if (!lostToVoid) {
                createMeteorCrater(group.level, impact, group.meteorSize);
                spawnImpactBurst(group.level, impact);
            }

            iterator.remove();
        }
    }
}

private static boolean willHitSomething(ServerLevel level, FallingBlockEntity meteor) {
    if (meteor.onGround()) {
        return true;
    }

    Vec3 next = meteor.position().add(meteor.getDeltaMovement());

    BlockPos ahead = BlockPos.containing(next.x, next.y, next.z);
    BlockPos below = BlockPos.containing(next.x, next.y - 0.5, next.z);

    return !level.getBlockState(ahead).isAir() || !level.getBlockState(below).isAir();
}

    private static void discardRemainingBlocks(MeteorGroup group) {
        for (FallingBlockEntity meteor : group.blocks) {
            clearMeteorBlock(group.level, meteor);
            if (meteor.isAlive()) {
                meteor.discard();
            }
        }
    }

    private static void clearMeteorBlock(ServerLevel level, FallingBlockEntity meteor) {
        BlockPos pos = meteor.blockPosition();
        if (level.getBlockState(pos).is(Blocks.MAGMA_BLOCK)) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

     private static void sendGroupTrail(MeteorGroup group, int aliveCount) {
        int step = Math.max(1, aliveCount / 8);
        int index = 0;

        for (FallingBlockEntity meteor : group.blocks) {
            if (!meteor.isAlive()) {
                continue;
            }

            if (index++ % step != 0) {
                continue;
            }

            sendTrailParticles(group.level, meteor.position());
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

   

    private static void createMeteorCrater(
            ServerLevel level,
            Vec3 position,
            int meteorSize
    ) {
        float explosionPower = switch (meteorSize) {
            case 3 ->
                5.0F;
            case 5 ->
                7.0F;
            case 9 ->
                12.0F;
            default ->
                6.0F;
        };

        level.explode(
                null,
                position.x,
                position.y,
                position.z,
                explosionPower,
                Level.ExplosionInteraction.BLOCK
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
        private final int meteorSize;
        private int age;

        private MeteorGroup(
                ServerLevel level,
                List<FallingBlockEntity> blocks,
                Vec3 lastKnownPosition,
                int meteorSize
        ) {
            this.level = level;
            this.blocks = blocks;
            this.lastKnownPosition = lastKnownPosition;
            this.meteorSize = meteorSize;
            this.age = 0;
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
