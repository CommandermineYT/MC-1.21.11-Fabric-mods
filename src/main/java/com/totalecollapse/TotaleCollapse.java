package com.totalecollapse;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.DoubleArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
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
    private static final int MAX_GROUP_LIFETIME_TICKS = 600;

    private static final Random RANDOM = new Random();
    private static final List<MeteorGroup> ACTIVE_METEORS = new ArrayList<>();
    private static final List<MeteorStorm> ACTIVE_STORMS = new ArrayList<>();

    // ---- Crater tuning ----
    private static final double CRATER_EDGE_WOBBLE = 1.6;
    private static final double CRATER_CRUST_BAND = 0.78;

    // ---- Shockwave tuning ----
    private static final int SHOCKWAVE_TRAVEL_TICKS = 12;
    private static final int SHOCKWAVE_MAX_AGE = 40;
    private static final double SHOCKWAVE_PEAK_KNOCKBACK = 1.5;
    private static final double SHOCKWAVE_WALL_HEIGHT = 6.0;

    // ---- Delayed boom tuning ----
    private static final double SPEED_OF_SOUND_BLOCKS_PER_TICK = 17.0;
    private static final double MAX_BOOM_DISTANCE = 400.0;

    private static final List<Shockwave> ACTIVE_SHOCKWAVES = new ArrayList<>();
    private static final List<DelayedBoom> PENDING_BOOMS = new ArrayList<>();

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

        // Static collections survive a world reload, so wipe them when the
        // integrated server shuts down.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ACTIVE_METEORS.clear();
            ACTIVE_STORMS.clear();
            ACTIVE_SHOCKWAVES.clear();
            PENDING_BOOMS.clear();
        });
    }

    private static void tickMeteors() {
        tickStorms();
        tickMeteorGroups();
        tickShockwaves();
        tickBooms();
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

        // Visual effect for breaking through the atmosphere
        spawnAtmosphericBreach(level, groupCenter, meteorSize);

        ACTIVE_METEORS.add(new MeteorGroup(level, blocks, groupCenter, meteorSize));
    }

    private static void spawnAtmosphericBreach(ServerLevel level, Vec3 pos, int meteorSize) {
    level.sendParticles(ParticleTypes.SONIC_BOOM, pos.x, pos.y, pos.z, 1, 0.0, 0.0, 0.0, 0.0);

    int ringPoints = 20 + meteorSize * 2;
    double ringRadius = meteorSize * 0.9;

    for (int i = 0; i < ringPoints; i++) {
        double angle = (Math.PI * 2.0 / ringPoints) * i;
        level.sendParticles(
                ParticleTypes.CLOUD,
                pos.x + Math.cos(angle) * ringRadius,
                pos.y,
                pos.z + Math.sin(angle) * ringRadius,
                1, 0.0, 0.0, 0.0, 0.04
        );
    }
}

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
                triggerShockwave(group.level, impact, group.meteorSize);
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
        int craterRadius = switch (meteorSize) {
            case 3 ->
                6;
            case 5 ->
                9;
            case 9 ->
                14;
            default ->
                7;
        };

        carveCrater(level, position, craterRadius);
        throwEjecta(level, position, craterRadius);
    }

    /**
     * Carves a bowl instead of letting vanilla's explosion chew a random hole.
     * The floor gets a blackstone shell, the outer band is crusted with magma,
     * and the rim is wobbled so it never reads as a perfect circle.
     */
    private static void carveCrater(ServerLevel level, Vec3 center, int craterRadius) {
        BlockPos origin = BlockPos.containing(center.x, center.y, center.z);
        int maxDepth = Math.max(2, craterRadius / 2);

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int dx = -craterRadius; dx <= craterRadius; dx++) {
            for (int dz = -craterRadius; dz <= craterRadius; dz++) {
                double horizontal = Math.sqrt((double) dx * dx + (double) dz * dz);
                double edge = craterRadius + (RANDOM.nextDouble() - 0.5) * CRATER_EDGE_WOBBLE;

                if (horizontal > edge) {
                    continue;
                }

                double normalized = horizontal / edge;

                // cos() gives a smooth bowl: deepest in the middle, flat at the rim.
                int depth = (int) Math.round(maxDepth * Math.cos(normalized * Math.PI / 2.0));

                for (int dy = -depth; dy <= 1; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);

                    if (!isCarvable(level, cursor)) {
                        continue;
                    }

                    level.setBlock(cursor, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }

                // Crust the floor one block below the carve.
                cursor.set(origin.getX() + dx, origin.getY() - depth - 1, origin.getZ() + dz);

                if (!isCarvable(level, cursor)) {
                    continue;
                }

                boolean scorched = normalized > CRATER_CRUST_BAND || RANDOM.nextInt(4) == 0;

                level.setBlock(
                        cursor,
                        scorched
                                ? Blocks.MAGMA_BLOCK.defaultBlockState()
                                : Blocks.BLACKSTONE.defaultBlockState(),
                        Block.UPDATE_CLIENTS
                );
            }
        }
    }

    /** True for blocks we are allowed to replace: solid, present, and breakable. */
    private static boolean isCarvable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);

        if (state.isAir() || state.is(Blocks.BEDROCK)) {
            return false;
        }

        return state.getDestroySpeed(level, pos) >= 0.0F;
    }

    /** Throws real falling blocks outward from the rim so debris rains down. */
    private static void throwEjecta(ServerLevel level, Vec3 center, int craterRadius) {
        int chunkCount = Math.min(24, craterRadius * 2);

        for (int i = 0; i < chunkCount; i++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double distance = craterRadius * (0.65 + RANDOM.nextDouble() * 0.45);

            double x = center.x + Math.cos(angle) * distance;
            double z = center.z + Math.sin(angle) * distance;

            int surface = level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING,
                    Mth.floor(x),
                    Mth.floor(z)
            );

            FallingBlockEntity debris = FallingBlockEntity.fall(
                    level,
                    BlockPos.containing(x, surface + 1.0, z),
                    RANDOM.nextBoolean()
                            ? Blocks.MAGMA_BLOCK.defaultBlockState()
                            : Blocks.BLACKSTONE.defaultBlockState()
            );

            debris.dropItem = false;
            debris.setHurtsEntities(2.0F, 6);

            double outward = 0.30 + RANDOM.nextDouble() * 0.35;

            debris.setDeltaMovement(
                    Math.cos(angle) * outward,
                    0.55 + RANDOM.nextDouble() * 0.40,
                    Math.sin(angle) * outward
            );
        }
    }

    // ------------------------------------------------------------------
    // Ground shockwave
    // ------------------------------------------------------------------
    private static void triggerShockwave(ServerLevel level, Vec3 impact, int meteorSize) {
        double maxRadius = switch (meteorSize) {
            case 3 ->
                12.0;
            case 5 ->
                20.0;
            case 9 ->
                32.0;
            default ->
                16.0;
        };

        float peakDamage = switch (meteorSize) {
            case 3 ->
                6.0F;
            case 5 ->
                11.0F;
            case 9 ->
                18.0F;
            default ->
                8.0F;
        };

        ACTIVE_SHOCKWAVES.add(
                new Shockwave(
                        level,
                        impact,
                        maxRadius,
                        maxRadius / SHOCKWAVE_TRAVEL_TICKS,
                        peakDamage,
                        SHOCKWAVE_PEAK_KNOCKBACK
                )
        );

        scheduleDistantBoom(level, impact, meteorSize);
    }

    private static void tickShockwaves() {
        Iterator<Shockwave> iterator = ACTIVE_SHOCKWAVES.iterator();

        while (iterator.hasNext()) {
            Shockwave wave = iterator.next();

            double inner = wave.radius;
            wave.radius = Math.min(wave.maxRadius, wave.radius + wave.speed);
            wave.age++;

            drawShockwaveRing(wave, inner, wave.radius);
            pushEntities(wave, inner, wave.radius);

            if (wave.radius >= wave.maxRadius || wave.age > SHOCKWAVE_MAX_AGE) {
                iterator.remove();
            }
        }
    }

    /** Draws the ring at terrain height so it hugs slopes instead of clipping. */
    private static void drawShockwaveRing(Shockwave wave, double inner, double outer) {
        double mid = (inner + outer) / 2.0;

        if (mid < 0.5) {
            return;
        }

        int samples = Math.max(16, (int) (mid * 5.0));
        double fade = Math.max(0.0, 1.0 - mid / wave.maxRadius);

        for (int i = 0; i < samples; i++) {
            double angle = (Math.PI * 2.0 / samples) * i;

            double x = wave.origin.x + Math.cos(angle) * mid;
            double z = wave.origin.z + Math.sin(angle) * mid;

            int surface = wave.level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING,
                    Mth.floor(x),
                    Mth.floor(z)
            );

            // A wall taller than this stops the wave being drawn up its face.
            if (surface > wave.origin.y + SHOCKWAVE_WALL_HEIGHT) {
                continue;
            }

            wave.level.sendParticles(
                    ParticleTypes.CLOUD,
                    x,
                    surface + 0.2,
                    z,
                    1,
                    0.12,
                    0.02,
                    0.12,
                    0.02 + 0.05 * fade
            );

            if (i % 3 != 0) {
                continue;
            }

            BlockPos groundPos = BlockPos.containing(x, surface - 1.0, z);
            BlockState ground = wave.level.getBlockState(groundPos);

            if (ground.isAir()) {
                continue;
            }

            wave.level.sendParticles(
                    new BlockParticleOption(ParticleTypes.BLOCK, ground),
                    x,
                    surface + 0.1,
                    z,
                    2,
                    0.15,
                    0.05,
                    0.15,
                    0.05
            );
        }
    }

    /** Shoves and damages anything caught in this tick's expanding band. */
    private static void pushEntities(Shockwave wave, double inner, double outer) {
        AABB band = new AABB(
                wave.origin.x - outer,
                wave.origin.y - 4.0,
                wave.origin.z - outer,
                wave.origin.x + outer,
                wave.origin.y + 6.0,
                wave.origin.z + outer
        );

        for (LivingEntity entity : wave.level.getEntitiesOfClass(LivingEntity.class, band)) {
            if (entity instanceof ServerPlayer player && (player.isSpectator() || player.isCreative())) {
                continue;
            }

            double dx = entity.getX() - wave.origin.x;
            double dz = entity.getZ() - wave.origin.z;
            double distance = Math.sqrt(dx * dx + dz * dz);

            if (distance > outer || distance < inner - 1.0) {
                continue;
            }

            // One hit per wave, no matter how many ticks it overlaps them.
            if (!wave.alreadyHit.add(entity.getUUID())) {
                continue;
            }

            double falloff = Math.max(0.0, 1.0 - distance / wave.maxRadius);

            double nx = distance < 0.1 ? RANDOM.nextDouble() - 0.5 : dx / distance;
            double nz = distance < 0.1 ? RANDOM.nextDouble() - 0.5 : dz / distance;

            double push = wave.peakKnockback * falloff;

            entity.push(nx * push, 0.15 + 0.35 * falloff, nz * push);
            entity.hurtMarked = true;

            float damage = (float) (wave.peakDamage * falloff);

            if (damage >= 0.5F) {
                entity.hurtServer(
                        wave.level,
                        wave.level.damageSources().explosion(null, null),
                        damage
                );
            }

            // Players ignore server-side velocity unless it is pushed to them.
            if (entity instanceof ServerPlayer player) {
                player.connection.send(new ClientboundSetEntityMotionPacket(player));
            }
        }
    }

    // ------------------------------------------------------------------
    // Delayed, distance-based boom
    // ------------------------------------------------------------------
    private static void scheduleDistantBoom(ServerLevel level, Vec3 impact, int meteorSize) {
        float volume = 3.0F + meteorSize * 0.5F;
        float pitch = Math.max(0.4F, 1.1F - meteorSize * 0.06F);

        for (ServerPlayer player : level.players()) {
            double distance = Math.sqrt(player.distanceToSqr(impact));

            if (distance > MAX_BOOM_DISTANCE) {
                continue;
            }

            int delay = (int) (distance / SPEED_OF_SOUND_BLOCKS_PER_TICK);

            if (delay <= 0) {
                playBoom(player, impact, volume, pitch);
                continue;
            }

            PENDING_BOOMS.add(
                    new DelayedBoom(player.getUUID(), level, impact, delay, volume, pitch)
            );
        }
    }

    private static void tickBooms() {
        Iterator<DelayedBoom> iterator = PENDING_BOOMS.iterator();

        while (iterator.hasNext()) {
            DelayedBoom boom = iterator.next();

            if (boom.ticksRemaining-- > 0) {
                continue;
            }

            ServerPlayer player = boom.level.getServer().getPlayerList().getPlayer(boom.playerId);

            if (player != null) {
                playBoom(player, boom.impact, boom.volume, boom.pitch);
            }

            iterator.remove();
        }
    }

    private static void playBoom(ServerPlayer player, Vec3 impact, float volume, float pitch) {
        Holder<SoundEvent> sound = SoundEvents.GENERIC_EXPLODE;

        player.connection.send(
                new ClientboundSoundPacket(
                        sound,
                        SoundSource.BLOCKS,
                        impact.x,
                        impact.y,
                        impact.z,
                        volume,
                        pitch,
                        RANDOM.nextLong()
                )
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

    private static final class Shockwave {

        private final ServerLevel level;
        private final Vec3 origin;
        private final double maxRadius;
        private final double speed;
        private final float peakDamage;
        private final double peakKnockback;
        private final Set<UUID> alreadyHit = new HashSet<>();

        private double radius;
        private int age;

        private Shockwave(
                ServerLevel level,
                Vec3 origin,
                double maxRadius,
                double speed,
                float peakDamage,
                double peakKnockback
        ) {
            this.level = level;
            this.origin = origin;
            this.maxRadius = maxRadius;
            this.speed = speed;
            this.peakDamage = peakDamage;
            this.peakKnockback = peakKnockback;
            this.radius = 0.0;
            this.age = 0;
        }
    }

    private static final class DelayedBoom {

        private final UUID playerId;
        private final ServerLevel level;
        private final Vec3 impact;
        private final float volume;
        private final float pitch;

        private int ticksRemaining;

        private DelayedBoom(
                UUID playerId,
                ServerLevel level,
                Vec3 impact,
                int ticksRemaining,
                float volume,
                float pitch
        ) {
            this.playerId = playerId;
            this.level = level;
            this.impact = impact;
            this.ticksRemaining = ticksRemaining;
            this.volume = volume;
            this.pitch = pitch;
        }
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
