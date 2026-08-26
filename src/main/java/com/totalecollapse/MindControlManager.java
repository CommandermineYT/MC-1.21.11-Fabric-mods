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
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;

public final class MindControlManager {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("totale-collapse-mindcontrol");

    private static final Set<UUID> AWAITING_TARGET = new HashSet<>();

    private static final Map<UUID, Possession> ACTIVE = new HashMap<>();

    private static final Set<UUID> POSSESSED_ENTITY_IDS = new HashSet<>();

    private static final double HOSTILE_PROTECTION_RADIUS = 48.0;

    /*
     * Movement settings.
     */
    private static final double WALK_SPEED = 0.22;
    private static final double SPRINT_SPEED = 0.32;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double GRAVITY = 0.08;

    private MindControlManager() {
    }

    // -------------------------------------------------------------------------
    // INPUT
    // -------------------------------------------------------------------------

    /**
     * Called whenever the player sends a movement-input packet.
     *
     * This is the important part: on modern Minecraft versions the server
     * receives WASD/jump input through ServerboundPlayerInputPacket rather
     * than us simply relying on player.xxa/player.zza.
     */
    public static void handleInput(
            ServerPlayer player,
            float strafe,
            float forward,
            boolean jumping,
            boolean sneaking
    ) {
        Possession possession = ACTIVE.get(player.getUUID());

        if (possession == null) {
            return;
        }

        possession.setInput(
                strafe,
                forward,
                jumping,
                sneaking
        );
    }

    // -------------------------------------------------------------------------
    // START
    // -------------------------------------------------------------------------

    public static void beginAwaitingTarget(ServerPlayer player) {
        UUID id = player.getUUID();

        if (ACTIVE.containsKey(id) || AWAITING_TARGET.contains(id)) {
            player.sendSystemMessage(Component.literal(
                    "You're already mind controlling something. "
                            + "Use /collapse MindControl Stop first."
            ));
            return;
        }

        AWAITING_TARGET.add(id);

        player.sendSystemMessage(Component.literal(
                "Right click an entity to take control of it."
        ));
    }

    public static boolean isAwaitingTarget(ServerPlayer player) {
        return AWAITING_TARGET.contains(player.getUUID());
    }

    public static boolean isPossessedEntity(Entity entity) {
        return entity instanceof LivingEntity
                && POSSESSED_ENTITY_IDS.contains(entity.getUUID());
    }

    // -------------------------------------------------------------------------
    // POSSESS
    // -------------------------------------------------------------------------

    public static void tryPossess(
            ServerPlayer player,
            Entity target
    ) {
        UUID id = player.getUUID();

        if (!AWAITING_TARGET.remove(id)) {
            return;
        }

        if (target instanceof ServerPlayer) {
            player.sendSystemMessage(Component.literal(
                    "You can't mind control other players."
            ));
            return;
        }

        if (!(target instanceof LivingEntity livingTarget)) {
            player.sendSystemMessage(Component.literal(
                    "You can't mind control that."
            ));
            return;
        }

        if (!livingTarget.isAlive()) {
            player.sendSystemMessage(Component.literal(
                    "That entity is dead."
            ));
            return;
        }

        if (POSSESSED_ENTITY_IDS.contains(livingTarget.getUUID())) {
            player.sendSystemMessage(Component.literal(
                    "That entity is already being controlled."
            ));
            return;
        }

        GameType previousMode =
                player.gameMode.getGameModeForPlayer();

        boolean previousNoAi =
                livingTarget instanceof Mob mob && mob.isNoAi();

        Possession possession = new Possession(
                id,
                livingTarget,
                previousMode,
                previousNoAi
        );

        ACTIVE.put(id, possession);
        POSSESSED_ENTITY_IDS.add(livingTarget.getUUID());

        /*
         * Disable mob AI while we directly control it.
         */
        if (livingTarget instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setTarget(null);
            mob.setAggressive(false);
        }

        /*
         * Spectator is useful here because it stops the real player entity
         * from interfering with the possessed entity.
         */
        player.gameMode.changeGameModeForPlayer(GameType.SPECTATOR);

        /*
         * Start the camera on the possessed entity.
         */
        player.connection.send(
                new ClientboundSetCameraPacket(livingTarget)
        );

        /*
         * Immediately synchronize rotation.
         */
        livingTarget.setYRot(player.getYRot());
        livingTarget.setYHeadRot(player.getYRot());
        livingTarget.setYBodyRot(player.getYRot());
        livingTarget.setXRot(player.getXRot());

        player.sendSystemMessage(Component.literal(
                "Mind controlling "
                        + livingTarget.getName().getString()
                        + ". Use /collapse MindControl Stop to release it."
        ));

        LOGGER.info(
                "{} began mind controlling {}",
                player.getName().getString(),
                livingTarget
        );
    }

    // -------------------------------------------------------------------------
    // STOP
    // -------------------------------------------------------------------------

    public static void stop(ServerPlayer player) {
        UUID id = player.getUUID();

        boolean wasAwaiting =
                AWAITING_TARGET.remove(id);

        Possession possession =
                ACTIVE.remove(id);

        if (possession != null) {
            POSSESSED_ENTITY_IDS.remove(
                    possession.target().getUUID()
            );

            release(player, possession);

            player.sendSystemMessage(Component.literal(
                    "Released control of "
                            + possession.target().getName().getString()
                            + "."
            ));

            return;
        }

        if (wasAwaiting) {
            player.sendSystemMessage(Component.literal(
                    "Cancelled — no longer waiting for a target."
            ));
            return;
        }

        player.sendSystemMessage(Component.literal(
                "You aren't mind controlling anything."
        ));
    }

    // -------------------------------------------------------------------------
    // RELEASE
    // -------------------------------------------------------------------------

    private static void release(
            ServerPlayer player,
            Possession possession
    ) {
        LivingEntity target = possession.target();

        /*
         * Restore the mob's previous AI state.
         */
        if (target.isAlive() && target instanceof Mob mob) {
            mob.setNoAi(possession.previousNoAi());
        }

        /*
         * Put the camera back onto the player.
         */
        player.connection.send(
                new ClientboundSetCameraPacket(player)
        );

        /*
         * Restore the player's original gamemode.
         */
        player.gameMode.changeGameModeForPlayer(
                possession.previousMode()
        );

        /*
         * Reset player movement.
         */
        player.setDeltaMovement(Vec3.ZERO);
    }

    // -------------------------------------------------------------------------
    // DISCONNECT
    // -------------------------------------------------------------------------

    public static void handleDisconnect(ServerPlayer player) {
        UUID playerId = player.getUUID();

        AWAITING_TARGET.remove(playerId);

        Possession possession =
                ACTIVE.remove(playerId);

        if (possession == null) {
            return;
        }

        LivingEntity target = possession.target();

        POSSESSED_ENTITY_IDS.remove(
                target.getUUID()
        );

        if (target.isAlive() && target instanceof Mob mob) {
            mob.setNoAi(
                    possession.previousNoAi()
            );
        }
    }

    // -------------------------------------------------------------------------
    // TICK
    // -------------------------------------------------------------------------

    public static void tick() {
        Iterator<Map.Entry<UUID, Possession>> iterator =
                ACTIVE.entrySet().iterator();

        while (iterator.hasNext()) {

            Possession possession =
                    iterator.next().getValue();

            ServerPlayer player =
                    possession.resolvePlayer();

            LivingEntity target =
                    possession.target();

            /*
             * Player disappeared or entity died.
             */
            if (player == null || !target.isAlive()) {

                if (player != null) {
                    release(player, possession);
                }

                POSSESSED_ENTITY_IDS.remove(
                        target.getUUID()
                );

                iterator.remove();
                continue;
            }

            /*
             * Make absolutely sure the client camera stays attached.
             */
            player.connection.send(
                    new ClientboundSetCameraPacket(target)
            );

            /*
             * The real player should not physically move while possessing.
             */
            player.setDeltaMovement(Vec3.ZERO);

            /*
             * The player's mouse controls the possessed entity.
             */
            updateRotation(player, target);

            /*
             * WASD / jump controls the possessed entity.
             */
            updateMovement(player, possession, target);

            /*
             * Stop hostile mobs attacking the thing we're controlling.
             */
            protectFromHostiles(target);
        }
    }

    // -------------------------------------------------------------------------
    // ROTATION
    // -------------------------------------------------------------------------

    private static void updateRotation(
            ServerPlayer player,
            LivingEntity target
    ) {
        float yaw = player.getYRot();
        float pitch = player.getXRot();

        /*
         * Clamp pitch to normal Minecraft camera limits.
         */
        pitch = Math.max(-90.0F, Math.min(90.0F, pitch));

        target.setYRot(yaw);
        target.setYHeadRot(yaw);
        target.setYBodyRot(yaw);
        target.setXRot(pitch);
    }

    // -------------------------------------------------------------------------
    // MOVEMENT
    // -------------------------------------------------------------------------

    private static void updateMovement(
            ServerPlayer player,
            Possession possession,
            LivingEntity target
    ) {
        float forward = possession.forward();
        float strafe = possession.strafe();

        /*
         * Sneaking slightly slows movement.
         */
        double speed = possession.sneaking()
                ? WALK_SPEED * 0.3
                : WALK_SPEED;

        /*
         * Use the player's sprint state for faster movement.
         */
        if (player.isSprinting() && !possession.sneaking()) {
            speed = SPRINT_SPEED;
        }

        /*
         * Calculate direction based on camera yaw.
         */
        double yawRadians =
                Math.toRadians(target.getYRot());

        double sin =
                Math.sin(yawRadians);

        double cos =
                Math.cos(yawRadians);

        /*
         * Forward/backward.
         */
        double moveX =
                -sin * forward;

        double moveZ =
                cos * forward;

        /*
         * Left/right.
         */
        moveX += cos * strafe;
        moveZ += sin * strafe;

        /*
         * Normalize diagonal movement.
         */
        double length =
                Math.sqrt(
                        moveX * moveX
                                + moveZ * moveZ
                );

        if (length > 1.0E-4) {
            moveX /= length;
            moveZ /= length;
        } else {
            moveX = 0.0;
            moveZ = 0.0;
        }

        Vec3 velocity =
                target.getDeltaMovement();

        double verticalVelocity =
                velocity.y;

        /*
         * Jump.
         */
        if (possession.jumping()
                && target.onGround()) {

            verticalVelocity =
                    JUMP_VELOCITY;
        }

        /*
         * Gravity.
         */
        if (!target.onGround()
                && !target.isNoGravity()) {

            verticalVelocity -= GRAVITY;
        }

        /*
         * Prevent ridiculous falling speeds.
         */
        if (verticalVelocity < -3.92) {
            verticalVelocity = -3.92;
        }

        /*
         * Horizontal movement.
         */
        double horizontalX =
                moveX * speed;

        double horizontalZ =
                moveZ * speed;

        /*
         * Apply air drag when there is no input.
         *
         * This gives a more natural stop rather than instantly
         * snapping to zero.
         */
        if (forward == 0.0F
                && strafe == 0.0F) {

            horizontalX *= 0.65;
            horizontalZ *= 0.65;
        }

        Vec3 newVelocity =
                new Vec3(
                        horizontalX,
                        verticalVelocity,
                        horizontalZ
                );

        target.setDeltaMovement(newVelocity);

        /*
         * Let Minecraft's collision system handle the actual movement.
         */
        target.move(
                MoverType.SELF,
                newVelocity
        );

        /*
         * Keep the entity's body facing the camera.
         */
        target.setYRot(player.getYRot());
        target.setYHeadRot(player.getYRot());

        if (target instanceof Mob mob) {
            mob.setYBodyRot(player.getYRot());
        }

        target.setXRot(player.getXRot());
    }

    // -------------------------------------------------------------------------
    // HOSTILE PROTECTION
    // -------------------------------------------------------------------------

    private static void protectFromHostiles(
            LivingEntity target
    ) {
        if (!(target.level()
                instanceof ServerLevel serverLevel)) {
            return;
        }

        AABB searchArea =
                target.getBoundingBox()
                        .inflate(
                                HOSTILE_PROTECTION_RADIUS
                        );

        serverLevel.getEntitiesOfClass(
                Mob.class,
                searchArea,
                mob ->
                        mob instanceof Enemy
                                && mob.getTarget() == target
        ).forEach(mob ->
                mob.setTarget(null)
        );
    }

    // -------------------------------------------------------------------------
    // POSSESSION DATA
    // -------------------------------------------------------------------------

    private static final class Possession {

        private final UUID playerId;

        private final LivingEntity target;

        private final GameType previousMode;

        private final boolean previousNoAi;

        /*
         * Latest client input.
         */
        private float strafe;

        private float forward;

        private boolean jumping;

        private boolean sneaking;

        private Possession(
                UUID playerId,
                LivingEntity target,
                GameType previousMode,
                boolean previousNoAi
        ) {
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

        private float strafe() {
            return strafe;
        }

        private float forward() {
            return forward;
        }

        private boolean jumping() {
            return jumping;
        }

        private boolean sneaking() {
            return sneaking;
        }

        private void setInput(
                float strafe,
                float forward,
                boolean jumping,
                boolean sneaking
        ) {
            this.strafe = strafe;
            this.forward = forward;
            this.jumping = jumping;
            this.sneaking = sneaking;
        }

        private ServerPlayer resolvePlayer() {
            if (target.level()
                    instanceof ServerLevel serverLevel) {

                return serverLevel
                        .getServer()
                        .getPlayerList()
                        .getPlayer(playerId);
            }

            return null;
        }
    }
}