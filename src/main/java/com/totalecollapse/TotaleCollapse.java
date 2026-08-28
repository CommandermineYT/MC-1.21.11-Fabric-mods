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
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.totalecollapse.content.ModItemGroups;
import com.totalecollapse.content.ModOres;
import com.totalecollapse.content.ModToolBehaviour;
import com.totalecollapse.content.ModTools;
import com.totalecollapse.content.ModWorldgen;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
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
    private static final int SHOCKWAVE_TRAVEL_TICKS = 14;
    private static final int SHOCKWAVE_MAX_AGE = 60;
    private static final double SHOCKWAVE_PEAK_KNOCKBACK = 1.9;
    private static final double SHOCKWAVE_WALL_HEIGHT = 6.0;

    /**
     * Falloff exponent. 1.0 is linear; below 1.0 keeps the wave dangerous
     * further out instead of fading to nothing halfway.
     */
    private static final double SHOCKWAVE_FALLOFF_EXPONENT = 0.6;

    // ---- Ground ripple tuning ----
    private static final double RIPPLE_RADIUS_FACTOR = 1.6;
    private static final double RIPPLE_SPEED = 0.9;
    private static final boolean RIPPLE_MOVES_BLOCKS = true;

    /** Hard ceiling on falling-block entities per ripple. */
    private static final int RIPPLE_MAX_BLOCKS = 2200;

    /** Upward launch speed at the impact centre, easing to zero at the edge. */
    private static final double RIPPLE_PEAK_LAUNCH = 0.62;
    private static final double RIPPLE_MIN_LAUNCH = 0.14;

    /** Shapes how fast the launch strength decays outward. */
    private static final double RIPPLE_LAUNCH_FALLOFF = 0.75;

    /** Small outward drift so the wave visibly travels instead of just bobbing. */
    private static final double RIPPLE_OUTWARD_DRIFT = 0.035;

    /** Beyond this, forced particles are not worth the packets. */
    private static final double PARTICLE_SEND_DISTANCE = 160.0;

    // ---- Delayed boom tuning ----
    private static final double SPEED_OF_SOUND_BLOCKS_PER_TICK = 17.0;
    private static final double MAX_BOOM_DISTANCE = 400.0;

    private static final List<Shockwave> ACTIVE_SHOCKWAVES = new ArrayList<>();
    private static final List<DelayedBoom> PENDING_BOOMS = new ArrayList<>();
    private static final List<Ripple> ACTIVE_RIPPLES = new ArrayList<>();

    @Override
    public void onInitialize() {

         ModOres.init();
         ModTools.init();            // ADD
         ModItemGroups.init();
         ModToolBehaviour.init();    // ADD
         ModWorldgen.init();
         StackItems.load();
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

        WorldEditor.register();

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
                                    Commands.literal("shockwave")
                                            .executes(context -> fireTestShockwave(context.getSource(), 9))
                                            .then(
                                                    Commands.argument("size", IntegerArgumentType.integer(1, 20))
                                                            .executes(context -> fireTestShockwave(
                                                                    context.getSource(),
                                                                    IntegerArgumentType.getInteger(context, "size")
                                                            ))
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

            dispatcher.register(
                    Commands.literal("~")
                            .then(Commands.argument("blk", StringArgumentType.string())
                                    .then(Commands.argument("cnt", IntegerArgumentType.integer(1, 64000))
                                            .executes(ctx -> {
                                                var src = ctx.getSource();
                                                ServerPlayer p;

                                                try {
                                                    p = src.getPlayerOrException();
                                                } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {
                                                    return Command.SINGLE_SUCCESS;
                                                }

                                                String id = StringArgumentType.getString(ctx, "blk");
                                                int n = IntegerArgumentType.getInteger(ctx, "cnt");

                                                var rl = Identifier.parse(id);
                                                var block = src.getLevel().registryAccess()
                                                        .lookupOrThrow(Registries.BLOCK)
                                                        .getValue(rl);

                                                if (block == null) {
                                                    return Command.SINGLE_SUCCESS;
                                                }

                                                var stack = new ItemStack(block.asItem(), n);

                                                if (!p.getInventory().add(stack)) {
                                                    p.drop(stack, false);
                                                }

                                                return Command.SINGLE_SUCCESS;
                                            })
                                    )
                            )
            );
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickMeteors();
            MindControlManager.tick();
            WorldEditor.tick(server);

        });

        // Static collections survive a world reload, so wipe them when the
        // integrated server shuts down.
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            ACTIVE_METEORS.clear();
            ACTIVE_STORMS.clear();
            ACTIVE_SHOCKWAVES.clear();
            PENDING_BOOMS.clear();
            ACTIVE_RIPPLES.clear();
            MindControlManager.clearAll();
            WorldEditor.clearAll();
        });
    }

    private static void tickMeteors() {
        tickStorms();
        tickMeteorGroups();
        tickShockwaves();
        tickRipples();
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
    forceParticles(level, ParticleTypes.SONIC_BOOM, pos.x, pos.y, pos.z, 1, 0.0, 0.0, 0.0, 0.0);

    int ringPoints = 20 + meteorSize * 2;
    double ringRadius = meteorSize * 0.9;

    for (int i = 0; i < ringPoints; i++) {
        double angle = (Math.PI * 2.0 / ringPoints) * i;
        forceParticles(level,
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
        forceParticles(level,
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

        forceParticles(level,
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
                18.0;
            case 5 ->
                30.0;
            case 9 ->
                46.0;
            default ->
                22.0;
        };

        float peakDamage = switch (meteorSize) {
            case 3 ->
                7.0F;
            case 5 ->
                13.0F;
            case 9 ->
                21.0F;
            default ->
                9.0F;
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

        // The flash at the moment of contact. FLASH is not usable here - in
        // 1.21.11 it is a ParticleType<ColorParticleOption>, not a plain
        // ParticleOptions, so a stacked EXPLOSION_EMITTER pair covers it.
        forceParticles(level, ParticleTypes.SONIC_BOOM, impact.x, impact.y + 0.5, impact.z, 1, 0.0, 0.0, 0.0, 0.0);
        forceParticles(level, ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y + 0.5, impact.z, 1, 0.0, 0.0, 0.0, 0.0);
        forceParticles(level, ParticleTypes.EXPLOSION_EMITTER, impact.x, impact.y + 2.5, impact.z, 1, 1.5, 0.5, 1.5, 0.0);
        forceParticles(level, ParticleTypes.EXPLOSION, impact.x, impact.y + 1.0, impact.z, 12, 2.0, 1.0, 2.0, 0.0);

        spawnGroundRipple(level, impact, maxRadius);
        scheduleDistantBoom(level, impact, meteorSize);
    }

    /** Test entry point for {@code /collapse shockwave [size]}. */
    private static int fireTestShockwave(net.minecraft.commands.CommandSourceStack source, int meteorSize) {
        ServerLevel level = source.getLevel();
        Vec3 origin = source.getPosition();

        triggerShockwave(level, origin, meteorSize);

        source.sendSuccess(
                () -> Component.literal("Shockwave fired at " + String.format("%.1f %.1f %.1f", origin.x, origin.y, origin.z)
                        + " (size " + meteorSize + ")"),
                true
        );

        return Command.SINGLE_SUCCESS;
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

    /**
     * Draws the ring at terrain height so it hugs slopes instead of clipping
     * through hills. Layered on purpose: a dust curtain for body, gusts for the
     * pressure front, block debris so it picks up whatever it crosses, and
     * flame plus explosion puffs at intervals for punch.
     */
    private static void drawShockwaveRing(Shockwave wave, double inner, double outer) {
        double mid = (inner + outer) / 2.0;

        if (mid < 0.5) {
            return;
        }

        int samples = Mth.clamp((int) (mid * 4.0), 24, 72);
        double fade = Math.max(0.0, 1.0 - mid / wave.maxRadius);
        double trailing = mid * 0.78;

        for (int i = 0; i < samples; i++) {
            double angle = (Math.PI * 2.0 / samples) * i;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);

            double x = wave.origin.x + cos * mid;
            double z = wave.origin.z + sin * mid;

            int surface = wave.level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING,
                    Mth.floor(x),
                    Mth.floor(z)
            );

            // A wall taller than this stops the wave being drawn up its face.
            if (surface > wave.origin.y + SHOCKWAVE_WALL_HEIGHT) {
                continue;
            }

            double baseY = surface + 0.1;

            // Curtain of dust: three stacked layers, thinning with height.
            forceParticles(wave.level, ParticleTypes.CLOUD, x, baseY + 0.2, z, 2, 0.16, 0.05, 0.16, 0.02 + 0.06 * fade);
            forceParticles(wave.level, ParticleTypes.LARGE_SMOKE, x, baseY + 0.9, z, 1, 0.2, 0.25, 0.2, 0.01);

            if (i % 2 == 0) {
                forceParticles(wave.level, ParticleTypes.LARGE_SMOKE, x, baseY + 1.7, z, 1, 0.25, 0.3, 0.25, 0.01);
            }

            // The pressure front itself.
            if (i % 4 == 0) {
                forceParticles(wave.level, ParticleTypes.GUST, x, baseY + 0.5, z, 1, 0.0, 0.0, 0.0, 0.0);
            }

            // Debris torn from whatever the wave is crossing.
            if (i % 3 == 0) {
                BlockState ground = wave.level.getBlockState(BlockPos.containing(x, surface - 1.0, z));

                if (!ground.isAir()) {
                    forceParticles(
                            wave.level,
                            new BlockParticleOption(ParticleTypes.BLOCK, ground),
                            x, baseY + 0.2, z, 3, 0.2, 0.1, 0.2, 0.12
                    );
                }
            }

            // Heat, only while the wave is still close and strong.
            if (fade > 0.35 && i % 6 == 0) {
                forceParticles(wave.level, ParticleTypes.FLAME, x, baseY + 0.4, z, 2, 0.15, 0.1, 0.15, 0.06);
            }

            if (fade > 0.5 && i % 12 == 0) {
                forceParticles(wave.level, ParticleTypes.EXPLOSION, x, baseY + 0.8, z, 1, 0.0, 0.0, 0.0, 0.0);
            }

            // A fainter second ring chasing the first sells the depth.
            if (trailing > 1.0 && i % 5 == 0) {
                double tx = wave.origin.x + cos * trailing;
                double tz = wave.origin.z + sin * trailing;

                int trailSurface = wave.level.getHeight(
                        Heightmap.Types.MOTION_BLOCKING,
                        Mth.floor(tx),
                        Mth.floor(tz)
                );

                forceParticles(wave.level, ParticleTypes.SMOKE, tx, trailSurface + 0.3, tz, 2, 0.2, 0.15, 0.2, 0.02);
            }
        }
    }

    /** Shoves and damages anything caught in this tick's expanding band. */
    private static void pushEntities(Shockwave wave, double inner, double outer) {
        AABB band = new AABB(
                wave.origin.x - outer,
                wave.origin.y - 6.0,
                wave.origin.z - outer,
                wave.origin.x + outer,
                wave.origin.y + 8.0,
                wave.origin.z + outer
        );

        for (LivingEntity entity : wave.level.getEntitiesOfClass(LivingEntity.class, band)) {
            // Spectators are untouchable. Creative players still get shoved,
            // they just don't take the damage - otherwise testing in creative
            // looks like the shockwave is doing nothing at all.
            if (entity instanceof ServerPlayer player && player.isSpectator()) {
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

            double linear = Math.max(0.0, 1.0 - distance / wave.maxRadius);
            double falloff = Math.pow(linear, SHOCKWAVE_FALLOFF_EXPONENT);

            double nx = distance < 0.1 ? RANDOM.nextDouble() - 0.5 : dx / distance;
            double nz = distance < 0.1 ? RANDOM.nextDouble() - 0.5 : dz / distance;

            double push = wave.peakKnockback * falloff;

            entity.push(nx * push, 0.20 + 0.40 * falloff, nz * push);
            entity.hurtMarked = true;

            boolean damageImmune = entity instanceof ServerPlayer player && player.isCreative();
            float damage = (float) (wave.peakDamage * falloff);

            if (!damageImmune && damage >= 0.5F) {
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
    // Ground ripple
    // ------------------------------------------------------------------
    private static void spawnGroundRipple(ServerLevel level, Vec3 impact, double shockwaveRadius) {
        ACTIVE_RIPPLES.add(new Ripple(level, impact, shockwaveRadius / RIPPLE_RADIUS_FACTOR));
    }

    private static void tickRipples() {
        Iterator<Ripple> iterator = ACTIVE_RIPPLES.iterator();

        while (iterator.hasNext()) {
            Ripple ripple = iterator.next();

            drawRipple(ripple);

            ripple.age++;
            ripple.radius = ripple.age * RIPPLE_SPEED;

            if (ripple.radius >= ripple.maxRadius) {
                iterator.remove();
            }
        }
    }

    /**
     * The real ground ripple. Every eligible surface block inside the wave
     * front is turned into a live falling-block entity that launches upward and
     * settles back down, so the terrain itself rolls outward from the impact.
     *
     * The band is scanned on the integer grid rather than by sampling angles.
     * Angular sampling leaves gaps that widen with radius; a grid scan hits
     * every column exactly once. Because the band width equals the wave speed,
     * a block's distance maps to exactly one tick - so nothing double-hops and
     * no bookkeeping set is needed.
     */
    private static void drawRipple(Ripple ripple) {
        double inner = ripple.age * RIPPLE_SPEED;
        double outer = inner + RIPPLE_SPEED;

        int reach = Mth.ceil(outer);

        int originX = Mth.floor(ripple.origin.x);
        int originZ = Mth.floor(ripple.origin.z);

        for (int offsetX = -reach; offsetX <= reach; offsetX++) {
            for (int offsetZ = -reach; offsetZ <= reach; offsetZ++) {
                double distance = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);

                // Exactly one band per column, so exactly one hop per column.
                if (distance < inner || distance >= outer) {
                    continue;
                }

                int x = originX + offsetX;
                int z = originZ + offsetZ;

                int surfaceY = ripple.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);

                BlockPos surfacePos = new BlockPos(x, surfaceY - 1, z);
                BlockState surface = ripple.level.getBlockState(surfacePos);

                if (surface.isAir()) {
                    continue;
                }

                // Strength eases out toward the rim, which is what makes it
                // read as a wave rather than a uniform pop.
                double nearness = Math.max(0.0, 1.0 - distance / ripple.maxRadius);
                double eased = Math.pow(nearness, RIPPLE_LAUNCH_FALLOFF);

                double launch = RIPPLE_MIN_LAUNCH
                        + (RIPPLE_PEAK_LAUNCH - RIPPLE_MIN_LAUNCH) * eased;

                forceParticles(
                        ripple.level,
                        new BlockParticleOption(ParticleTypes.BLOCK, surface),
                        x + 0.5, surfaceY + 0.1, z + 0.5,
                        1, 0.25, 0.05, 0.25, 0.04
                );

                if (!RIPPLE_MOVES_BLOCKS || ripple.blocksMoved >= RIPPLE_MAX_BLOCKS) {
                    continue;
                }

                if (!canHop(ripple.level, surfacePos, surface)) {
                    continue;
                }

                // fall() lifts the block out and re-places it on landing,
                // which is exactly the pop-and-settle we want.
                FallingBlockEntity hop = FallingBlockEntity.fall(ripple.level, surfacePos, surface);

                hop.dropItem = false;

                double drift = distance < 0.5 ? 0.0 : RIPPLE_OUTWARD_DRIFT * eased;

                hop.setDeltaMovement(
                        (offsetX / Math.max(0.5, distance)) * drift,
                        launch * (0.88 + RANDOM.nextDouble() * 0.24),
                        (offsetZ / Math.max(0.5, distance)) * drift
                );

                ripple.blocksMoved++;
            }
        }
    }

    /** Only hop plain, full, breakable blocks - never containers or fluids. */
    private static boolean canHop(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.hasBlockEntity() || !state.getFluidState().isEmpty()) {
            return false;
        }

        if (!state.isCollisionShapeFullBlock(level, pos)) {
            return false;
        }

        if (!level.getBlockState(pos.above()).isAir()) {
            return false;
        }

        return isCarvable(level, pos);
    }

    // ------------------------------------------------------------------
    // Delayed, distance-based boom
    // ------------------------------------------------------------------
    private static void scheduleDistantBoom(ServerLevel level, Vec3 impact, int meteorSize) {
        float pitch = Math.max(0.4F, 1.05F - meteorSize * 0.05F);

        for (ServerPlayer player : level.players()) {
            double distance = Math.sqrt(player.distanceToSqr(impact));

            if (distance > MAX_BOOM_DISTANCE) {
                continue;
            }

            int delay = (int) (distance / SPEED_OF_SOUND_BLOCKS_PER_TICK);

            if (delay <= 0) {
                playBoom(player, impact, pitch);
                continue;
            }

            PENDING_BOOMS.add(new DelayedBoom(player.getUUID(), level, impact, delay, pitch));
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
                playBoom(player, boom.impact, boom.pitch);
            }

            iterator.remove();
        }
    }

    /**
     * Plays the boom at the listener's own position rather than at the impact.
     * Minecraft's audible radius is volume x 16 blocks, and volume above 1.0
     * only widens that radius without adding loudness - so an impact 150 blocks
     * out played at its real coordinates is silent or nearly so. Emitting it on
     * the player and scaling volume by distance is how vanilla handles thunder.
     */
    private static void playBoom(ServerPlayer player, Vec3 impact, float basePitch) {
        double distance = Math.sqrt(player.distanceToSqr(impact));
        double nearness = 1.0 - Math.min(1.0, distance / MAX_BOOM_DISTANCE);

        float volume = (float) (0.25 + 0.75 * nearness);
        float pitch = Math.max(0.35F, basePitch - (float) ((1.0 - nearness) * 0.45));

        Vec3 ear = player.position();

        player.connection.send(
                new ClientboundSoundPacket(
                        SoundEvents.GENERIC_EXPLODE,
                        SoundSource.BLOCKS,
                        ear.x,
                        ear.y,
                        ear.z,
                        volume,
                        pitch,
                        RANDOM.nextLong()
                )
        );

        // A second, much lower layer gives the distant rumble some body.
        player.connection.send(
                new ClientboundSoundPacket(
                        SoundEvents.GENERIC_EXPLODE,
                        SoundSource.BLOCKS,
                        ear.x,
                        ear.y,
                        ear.z,
                        volume * 0.85F,
                        Math.max(0.25F, pitch * 0.45F),
                        RANDOM.nextLong()
                )
        );
    }

    private static void spawnImpactBurst(ServerLevel level, Vec3 position) {
        BlockParticleOption dustPillar = new BlockParticleOption(
                ParticleTypes.DUST_PILLAR,
                Blocks.MAGMA_BLOCK.defaultBlockState()
        );

        forceParticles(level,
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

        forceParticles(level,
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

        forceParticles(level,
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

    /**
     * Sends a particle to every player with force = true.
     * The plain sendParticles(...) overload uses force = false, which the server
     * clamps to a 32-block radius - that is why effects on meteors spawned 30-60
     * blocks up and 25-150 blocks out were invisible. Forcing extends the range
     * to 512 blocks and makes the particle show even on the "Minimal" setting.
     * Players past PARTICLE_SEND_DISTANCE are skipped so a full storm doesn't
     * flood the network with particles nobody can resolve anyway.
     */
    private static <T extends ParticleOptions> void forceParticles(
            ServerLevel level,
            T type,
            double x,
            double y,
            double z,
            int count,
            double spreadX,
            double spreadY,
            double spreadZ,
            double speed
    ) {
        double cutoffSquared = PARTICLE_SEND_DISTANCE * PARTICLE_SEND_DISTANCE;

        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(x, y, z) > cutoffSquared) {
                continue;
            }

            level.sendParticles(player, type, true, true, x, y, z, count, spreadX, spreadY, spreadZ, speed);
        }
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
        private final float pitch;

        private int ticksRemaining;

        private DelayedBoom(
                UUID playerId,
                ServerLevel level,
                Vec3 impact,
                int ticksRemaining,
                float pitch
        ) {
            this.playerId = playerId;
            this.level = level;
            this.impact = impact;
            this.ticksRemaining = ticksRemaining;
            this.pitch = pitch;
        }
    }

    private static final class Ripple {

        private final ServerLevel level;
        private final Vec3 origin;
        private final double maxRadius;

        private double radius;
        private int age;
        private int blocksMoved;

        private Ripple(ServerLevel level, Vec3 origin, double maxRadius) {
            this.level = level;
            this.origin = origin;
            this.maxRadius = maxRadius;
            this.radius = 0.0;
            this.age = 0;
            this.blocksMoved = 0;
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
