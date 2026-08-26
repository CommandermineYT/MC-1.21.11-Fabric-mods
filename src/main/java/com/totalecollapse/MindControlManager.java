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
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public final class MindControlManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("totale-collapse-mindcontrol");

    private static final Set<UUID> AWAITING_TARGET = new HashSet<>();
    private static final Map<UUID, Possession> ACTIVE = new HashMap<>();

    private MindControlManager() {
    }

    public static void beginAwaitingTarget(ServerPlayer player) {
        UUID id = player.getUUID();

        if (ACTIVE.containsKey(id)) {
            player.sendSystemMessage(Component.literal(
                    "You're already mind controlling something. Use /collapse MindControll Stop first."));
            return;
        }

        AWAITING_TARGET.add(id);
        player.sendSystemMessage(Component.literal("Right click an entity to take control of it."));
    }

    public static boolean isAwaitingTarget(ServerPlayer player) {
        return AWAITING_TARGET.contains(player.getUUID());
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

        ACTIVE.put(id, new Possession(id, livingTarget, previousMode, previousNoAi, player.position()));

        if (livingTarget instanceof Mob mob) {
            mob.setNoAi(true);
        }

        player.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);
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
        if (possession != null && possession.target().isAlive() && possession.target() instanceof Mob mob) {
            mob.setNoAi(possession.previousNoAi());
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
                iterator.remove();
                continue;
            }

            Vec3 currentPos = player.position();
            Vec3 delta = currentPos.subtract(possession.lastPlayerPos());

            if (delta.horizontalDistanceSqr() > 1.0E-6) {
                target.move(MoverType.SELF, new Vec3(delta.x, 0.0, delta.z));
            }

            target.setYRot(player.getYRot());
            target.setYHeadRot(player.getYRot());
            target.setYBodyRot(player.getYRot());
            target.setXRot(player.getXRot());

            player.teleportTo(target.getX(), target.getY(), target.getZ());
            possession.setLastPlayerPos(player.position());
        }
    }

    private static final class Possession {

        private final UUID playerId;
        private final LivingEntity target;
        private final GameType previousMode;
        private final boolean previousNoAi;
        private Vec3 lastPlayerPos;

        private Possession(UUID playerId, LivingEntity target, GameType previousMode, boolean previousNoAi, Vec3 lastPlayerPos) {
            this.playerId = playerId;
            this.target = target;
            this.previousMode = previousMode;
            this.previousNoAi = previousNoAi;
            this.lastPlayerPos = lastPlayerPos;
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

        private Vec3 lastPlayerPos() {
            return lastPlayerPos;
        }

        private void setLastPlayerPos(Vec3 pos) {
            this.lastPlayerPos = pos;
        }

        private ServerPlayer resolvePlayer() {
            if (target.level() instanceof ServerLevel serverLevel) {
                return serverLevel.getServer().getPlayerList().getPlayer(playerId);
            }
            return null;
        }
    }
}
