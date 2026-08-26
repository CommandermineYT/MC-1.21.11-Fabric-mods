package com.totalecollapse;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class MindControlManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("totale-collapse-mindcontrol");

    private static final Set<UUID> AWAITING_TARGET = new HashSet<>();
    private static final Map<UUID, Possession> ACTIVE = new HashMap<>();
    private static final Set<UUID> POSSESSED_ENTITY_IDS = new HashSet<>();

    private static final double HOSTILE_PROTECTION_RADIUS = 48.0;

    private MindControlManager() {
    }

    public static void beginAwaitingTarget(ServerPlayer player) {
        UUID id = player.getUUID();

        if (ACTIVE.containsKey(id) || AWAITING_TARGET.contains(id)) {
            player.sendSystemMessage(Component.literal(
                "You're already mind controlling something. Use /collapse MindControl Stop first."));
            return;
        }

        AWAITING_TARGET.add(id);
        player.sendSystemMessage(Component.literal("Right click an entity to take control of it."));
    }

    public static boolean isAwaitingTarget(ServerPlayer player) {
        return AWAITING_TARGET.contains(player.getUUID());
    }

    public static boolean isPossessedEntity(Entity entity) {
        return entity instanceof LivingEntity livingEntity && POSSESSED_ENTITY_IDS.contains(livingEntity.getUUID());
    }

    public static void tryPossess(ServerPlayer player, Entity target) {
        UUID id = player.getUUID();

        if (!AWAITING_TARGET.remove(id)) {
            return;
        }

        if (target instanceof ServerPlayer) {
            player.sendSystemMessage(Component.literal("You can't mind control other players."));
            return;
        }

        if (!(target instanceof LivingEntity livingTarget)) {
            player.sendSystemMessage(Component.literal("You can't mind control that."));
            return;
        }

        GameType previousMode = player.gameMode.getGameModeForPlayer();
        boolean previousNoAi = livingTarget instanceof Mob mob && mob.isNoAi();

        ACTIVE.put(id, new Possession(id, livingTarget, previousMode, previousNoAi));
        POSSESSED_ENTITY_IDS.add(livingTarget.getUUID());

        if (livingTarget instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setTarget(null);
        }

        player.connection.send(new ClientboundSetCameraPacket(livingTarget));

        player.sendSystemMessage(Component.literal(
            "Mind controlling " + livingTarget.getName().getString() + ". Use /collapse MindControll Stop to release it."));

        LOGGER.info("{} began mind controlling {}", player.getName().getString(), livingTarget);
    }

    public static void stop(ServerPlayer player) {
        UUID id = player.getUUID();

        boolean wasAwaiting = AWAITING_TARGET.remove(id);
        Possession possession = ACTIVE.remove(id);

        if (possession != null) {
            POSSESSED_ENTITY_IDS.remove(possession.target().getUUID());
            release(player, possession);
            player.sendSystemMessage(Component.literal(
                "Released control of " + possession.target().getName().getString() + "."));
            return;
        }

        if (wasAwaiting) {
            player.sendSystemMessage(Component.literal("Cancelled — no longer waiting for a target."));
            return;
        }

        player.sendSystemMessage(Component.literal("You aren't mind controlling anything."));
    }

    private static void release(ServerPlayer player, Possession possession) {
        if (possession.target().isAlive() && possession.target() instanceof Mob mob) {
            mob.setNoAi(possession.previousNoAi());
        }

        player.connection.send(new ClientboundSetCameraPacket(player));
        player.gameMode.changeGameModeForPlayer(possession.previousMode());
    }

    public static void handleDisconnect(ServerPlayer player) {
        AWAITING_TARGET.remove(player.getUUID());

        Possession possession = ACTIVE.remove(player.getUUID());
        if (possession != null) {
            POSSESSED_ENTITY_IDS.remove(possession.target().getUUID());
            if (possession.target().isAlive() && possession.target() instanceof Mob mob) {
                mob.setNoAi(possession.previousNoAi());
            }
        }
    }

    public static void tick() {
        Iterator<Map.Entry<UUID, Possession>> iterator = ACTIVE.entrySet().iterator();

        while (iterator.hasNext()) {
            Possession possession = iterator.next().getValue();

            ServerPlayer player = possession.resolvePlayer();
            LivingEntity target = possession.target();

            if (player == null || !target.isAlive()) {
                if (player != null) {
                    release(player, possession);
                }
                POSSESSED_ENTITY_IDS.remove(target.getUUID());
                iterator.remove();
                continue;
            }

            float forward = player.zza;
            float strafe = player.xxa;

            double WALK_SPEED = 0.3;
            double yawRad = Math.toRadians(player.getYRot());
            double sin = Math.sin(yawRad);
            double cos = Math.cos(yawRad);

            Vec3 forwardVec = new Vec3(-sin, 0.0, cos);
            Vec3 strafeVec = new Vec3(cos, 0.0, sin).scale(strafe);

            Vec3 motion;
            if (forward == 0.0F && strafe == 0.0F) {
                motion = Vec3.ZERO;
            } else {
                motion = forwardVec.scale(forward).add(strafeVec);
                double len = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
                if (len > 1.0E-6) {
                    motion = motion.scale(WALK_SPEED / len);
                } else {
                    motion = Vec3.ZERO;
                }
            }

            double verticalVel = target.getDeltaMovement().y;
            motion = new Vec3(motion.x, verticalVel, motion.z);

            target.setDeltaMovement(motion);
            target.move(MoverType.SELF, motion);

            target.setYRot(player.getYRot());
            target.setYHeadRot(player.getYRot());
            target.setYBodyRot(player.getYRot());
            target.setXRot(player.getXRot());

            protectFromHostiles(target);
        }
    }

    private static void protectFromHostiles(LivingEntity target) {
        if (!(target.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        AABB searchArea = target.getBoundingBox().inflate(HOSTILE_PROTECTION_RADIUS);

        serverLevel.getEntitiesOfClass(
                Mob.class,
                searchArea,
                mob -> mob instanceof Enemy && mob.getTarget() == target
            )
            .forEach(mob -> mob.setTarget(null));
    }

    private static final class Possession {
        private final UUID playerId;
        private final LivingEntity target;
        private final GameType previousMode;
        private final boolean previousNoAi;

        private Possession(UUID playerId, LivingEntity target, GameType previousMode, boolean previousNoAi) {
            this.playerId = playerId;
            this.target = target;
            this.previousMode = previousMode;
            this.previousNoAi = previousNoAi;
        }

        private LivingEntity target() {
            return target;
        }

        private GameType previousMode() {
            return previousMode;
        }

        private boolean previousNoAi() {
            return previousNoAi;
        }

        private ServerPlayer resolvePlayer() {
            if (target.level() instanceof ServerLevel serverLevel) {
                return serverLevel.getServer().getPlayerList().getPlayer(playerId);
            }
            return null;
        }
    }
}
