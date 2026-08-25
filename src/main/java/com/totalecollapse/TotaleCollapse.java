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
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
 
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class TotaleCollapse implements ModInitializer {
    public static final String MOD_ID = "totale-collapse";
    public static final Identifier LIGHTNING_PACKET = Identifier.fromNamespaceAndPath(MOD_ID, "lightning_request");
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
    private static final Map<UUID, Long> LAST_LIGHTNING_TIME = new HashMap<>();
    private static final long LIGHTNING_COOLDOWN_MS = 2000L;
    private static final double LIGHTNING_MAX_DISTANCE_SQ = 100.0 * 100.0; // 100 blocks

    @Override
    public void onInitialize() {
        try {
            Class<?> spnClass = ServerPlayNetworking.class;
            java.lang.reflect.Method[] methods = spnClass.getMethods();
            java.lang.reflect.Method target = null;
            for (java.lang.reflect.Method m : methods) {
                if (!m.getName().equals("registerGlobalReceiver")) continue;
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 2) { target = m; break; }
            }

            if (target != null) {
                ClassLoader cl = TotaleCollapse.class.getClassLoader();
                Class<?> handlerIface = target.getParameterTypes()[1];
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(cl, new Class<?>[]{handlerIface}, (proxyObj, method, args) -> {
                    try {
                        net.minecraft.network.FriendlyByteBuf buf = null;
                        net.minecraft.server.MinecraftServer server = null;
                        Object player = null;

                        if (args != null) {
                            for (Object a : args) {
                                if (a == null) continue;
                                if (a instanceof net.minecraft.network.FriendlyByteBuf) buf = (net.minecraft.network.FriendlyByteBuf) a;
                                if (a instanceof net.minecraft.server.MinecraftServer) server = (net.minecraft.server.MinecraftServer) a;
                                if (a instanceof net.minecraft.server.level.ServerPlayer) player = (net.minecraft.server.level.ServerPlayer) a;
                            }
                        }

                        if (buf == null) {
                            // try to find a FriendlyByteBuf inside a CustomPacketPayload argument
                            if (args != null && args.length > 0 && args[0] != null) {
                                Object payload = args[0];
                                try {
                                    java.lang.reflect.Method enc = payload.getClass().getMethod("encode");
                                    Object maybeBuf = enc.invoke(payload);
                                    if (maybeBuf instanceof net.minecraft.network.FriendlyByteBuf) {
                                        buf = (net.minecraft.network.FriendlyByteBuf) maybeBuf;
                                    }
                                } catch (NoSuchMethodException ignore) { } catch (Throwable invokeEx) { }
                                // fallback: look for a field of type FriendlyByteBuf
                                for (java.lang.reflect.Field f : payload.getClass().getDeclaredFields()) {
                                    if (net.minecraft.network.FriendlyByteBuf.class.isAssignableFrom(f.getType())) {
                                        f.setAccessible(true);
                                        Object val = f.get(payload);
                                        if (val instanceof net.minecraft.network.FriendlyByteBuf) { buf = (net.minecraft.network.FriendlyByteBuf) val; break; }
                                    }
                                }
                            }
                        }

                        if (buf == null) return null; // can't read payload

                        double x = buf.readDouble();
                        double y = buf.readDouble();
                        double z = buf.readDouble();

                        if (server == null && player != null) {
                            try {
                                java.lang.reflect.Method gs = player.getClass().getMethod("getServer");
                                Object srv = gs.invoke(player);
                                if (srv instanceof net.minecraft.server.MinecraftServer) server = (net.minecraft.server.MinecraftServer) srv;
                            } catch (Throwable ignore) {
                                try {
                                    java.lang.reflect.Field f = player.getClass().getField("server");
                                    Object srv = f.get(player);
                                    if (srv instanceof net.minecraft.server.MinecraftServer) server = (net.minecraft.server.MinecraftServer) srv;
                                } catch (Throwable ignore2) { }
                            }
                        }

                        if (server == null) return null;

                        net.minecraft.server.MinecraftServer finalServer = server;
                        Object finalPlayer = player;
                        finalServer.execute(() -> {
                            if (finalPlayer == null) return;
                            try {
                                java.lang.reflect.Method getUUID = finalPlayer.getClass().getMethod("getUUID");
                                UUID playerId = (UUID) getUUID.invoke(finalPlayer);

                                long now = System.currentTimeMillis();
                                Long last = LAST_LIGHTNING_TIME.get(playerId);
                                if (last != null && now - last < LIGHTNING_COOLDOWN_MS) {
                                    String name = finalPlayer.toString();
                                    try { java.lang.reflect.Method gn = finalPlayer.getClass().getMethod("getName"); Object nobj = gn.invoke(finalPlayer); name = String.valueOf(nobj); } catch (Throwable ignore) {}
                                    LOGGER.info("Ignoring lightning request from {} due to cooldown", name);
                                    return;
                                }

                                Object playerPosObj = null;
                                try { java.lang.reflect.Method posm = finalPlayer.getClass().getMethod("position"); playerPosObj = posm.invoke(finalPlayer); } catch (Throwable t) { }
                                double px = 0.0, pz = 0.0;
                                if (playerPosObj != null) {
                                    try {
                                        java.lang.reflect.Field fx = playerPosObj.getClass().getField("x");
                                        java.lang.reflect.Field fz = playerPosObj.getClass().getField("z");
                                        px = fx.getDouble(playerPosObj);
                                        pz = fz.getDouble(playerPosObj);
                                    } catch (Throwable t) {
                                        try {
                                            java.lang.reflect.Method mx = playerPosObj.getClass().getMethod("x");
                                            java.lang.reflect.Method mz = playerPosObj.getClass().getMethod("z");
                                            px = ((Number) mx.invoke(playerPosObj)).doubleValue();
                                            pz = ((Number) mz.invoke(playerPosObj)).doubleValue();
                                        } catch (Throwable t2) { }
                                    }
                                }

                                double dx = px - x;
                                double dz = pz - z;
                                double distSq = dx*dx + dz*dz;

                                String name = finalPlayer.toString();
                                try { java.lang.reflect.Method gn = finalPlayer.getClass().getMethod("getName"); Object nobj = gn.invoke(finalPlayer); name = String.valueOf(nobj); } catch (Throwable ignore) {}

                                if (distSq > LIGHTNING_MAX_DISTANCE_SQ) {
                                    LOGGER.info("Ignoring lightning request from {}: target too far", name);
                                    return;
                                }

                                try {
                                    java.lang.reflect.Method hasPerm = finalPlayer.getClass().getMethod("hasPermissions", int.class);
                                    Boolean allowed = (Boolean) hasPerm.invoke(finalPlayer, 2);
                                    if (!allowed) {
                                        LOGGER.info("Ignoring lightning request from {}: insufficient permissions", name);
                                        return;
                                    }
                                } catch (NoSuchMethodException ns) {
                                    try {
                                        java.lang.reflect.Method hasPerm2 = finalPlayer.getClass().getMethod("hasPermission", int.class);
                                        Boolean allowed = (Boolean) hasPerm2.invoke(finalPlayer, 2);
                                        if (!allowed) {
                                            LOGGER.info("Ignoring lightning request from {}: insufficient permissions", name);
                                            return;
                                        }
                                    } catch (Throwable ignore) { }
                                } catch (Throwable ignore) { }

                                LAST_LIGHTNING_TIME.put(playerId, now);

                                Object lvlObj = null;
                                try { java.lang.reflect.Method gl = finalPlayer.getClass().getMethod("getLevel"); lvlObj = gl.invoke(finalPlayer); } catch (Throwable t) { }
                                if (lvlObj == null) {
                                    try { java.lang.reflect.Field lf = finalPlayer.getClass().getField("level"); lvlObj = lf.get(finalPlayer); } catch (Throwable t) { }
                                }

                                if (!(lvlObj instanceof ServerLevel)) return;
                                ServerLevel level = (ServerLevel) lvlObj;
                                LightningStrike.triggerLightning(level, new Vec3(x, y, z));
                            } catch (Throwable t) {
                                LOGGER.error("Error handling lightning request", t);
                            }
                        });
                    } catch (Throwable t) {
                        LOGGER.error("Error in lightning packet handler", t);
                    }
                    return null;
                });

                // prepare first argument matching the target parameter type
                Object firstArg = LIGHTNING_PACKET;
                Class<?> p0 = target.getParameterTypes()[0];
                try {
                    if (!p0.isInstance(LIGHTNING_PACKET)) {
                        try {
                            Class<?> cpp = Class.forName("net.minecraft.network.protocol.common.custom.CustomPacketPayload");
                            java.lang.reflect.Method createType = cpp.getMethod("createType", String.class);
                            firstArg = createType.invoke(null, LIGHTNING_PACKET.toString());
                        } catch (ClassNotFoundException | NoSuchMethodException ignore) {
                            // fallback: leave Identifier as-is
                        }
                    }
                } catch (Throwable e) {
                    // ignore and fallback
                }

                // invoke registerGlobalReceiver via reflection to avoid compile-time signature mismatch
                target.invoke(null, firstArg, proxy);
            } else {
                LOGGER.error("No suitable registerGlobalReceiver overload found");
            }
        } catch (Throwable t) {
            LOGGER.error("Failed to register lightning packet receiver", t);
        }
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
    Commands.literal("lightning")
        .executes(context -> {
            ServerLevel level = context.getSource().getLevel();
            Vec3 origin = context.getSource().getPosition();

            for (int i = 0; i < 6; i++) {
                double angle = RANDOM.nextDouble() * Math.PI * 2.0;
                double distance = 2.0 + RANDOM.nextDouble() * 10.0;

                double x = origin.x + Math.cos(angle) * distance;
                double z = origin.z + Math.sin(angle) * distance;

                LightningStrike.triggerLightning(level, new Vec3(x, origin.y, z));
            }

            LOGGER.info("Lightning storm summoned at {}", origin);

            return Command.SINGLE_SUCCESS;
        })
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
                    .then(
                        Commands.literal("earth")
                            .executes(context -> {
                                ServerLevel level = context.getSource().getLevel();
                                Vec3 origin = context.getSource().getPosition();

                                EarthWave.triggerEarthWave(level, origin);

                                LOGGER.info("Earth shockwave cast at {}", origin);

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

            for (int x = 0; x < meteorSize; x++) {
                for (int y = 0; y < meteorSize; y++) {
                    for (int z = 0; z < meteorSize; z++) {
                     double offsetX = x - center;
                     double offsetY = y - center;
                    double offsetZ = z - center;

                     double distanceSquared =
                    offsetX * offsetX
                    + offsetY * offsetY
                    + offsetZ * offsetZ;

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

        ACTIVE_METEORS.add(
    new MeteorGroup(level, blocks, groupCenter, meteorSize)
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

    Vec3 meteorPosition = meteor.position();
    BlockPos blockBelow = BlockPos.containing(
        meteorPosition.x,
        meteorPosition.y - 0.6,
        meteorPosition.z
    );

    if (!group.level.getBlockState(blockBelow).isAir()) {
        group.lastKnownPosition = meteorPosition;
        meteor.discard();
        continue;
    }

    anyBlockIsAlive = true;
    trailPosition = meteorPosition;

    sendTrailParticles(group.level, trailPosition);
}

            if (!anyBlockIsAlive) {
    createMeteorCrater(
    group.level,
    group.lastKnownPosition,
    group.meteorSize
);
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

    private static void createMeteorCrater(
    ServerLevel level,
    Vec3 position,
    int meteorSize
) {
    float explosionPower = switch (meteorSize) {
        case 3 -> 5.0F;
        case 5 -> 7.0F;
        case 9 -> 12.0F;
        default -> 6.0F;
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

