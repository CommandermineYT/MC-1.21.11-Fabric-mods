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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class MindControlManager {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("totale-collapse-mindcontrol");

    private static final Set<UUID> AWAITING_TARGET =
            new HashSet<>();

    private static final Map<UUID, Possession> ACTIVE =
            new HashMap<>();

    private static final Set<UUID> POSSESSED_ENTITY_IDS =
            new HashSet<>();

    private static final double HOSTILE_PROTECTION_RADIUS = 48.0;

    private static final double WALK_SPEED = 0.22;
    private static final double SPRINT_SPEED = 0.32;
    private static final double JUMP_VELOCITY = 0.42;
    private static final double GRAVITY = 0.08;

    private MindControlManager() {
    }

    // ========================================================================
    // TARGET SELECTION
    // ========================================================================

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

    // ========================================================================
    // INPUT + ROTATION POLLING
    // ========================================================================

    /**
     * Reads the controlling player's live input and camera angles.
     *
     * No mixin is needed: the server already stores the last input packet on
     * ServerPlayer, and handleMovePlayer has already written the new rotation
     * onto the player by the time END_SERVER_TICK runs. Polling here is
     * therefore exactly as fresh as a HEAD injection into the packet handlers.
     */
    private static void pollControls(
            ServerPlayer player,
            Possession possession
    ) {
        Input input =
                player.getLastClientInput();

        possession.setInput(
                input.forward(),
                input.backward(),
                input.left(),
                input.right(),
                input.jump(),
                input.shift(),
                input.sprint()
        );

        /*
         * Read rotation from the PLAYER, not the target.
         * Mouse input updates the player's yaw/pitch even in spectator mode.
         */
        float yaw =
                player.getYRot();

        float pitch =
                Mth.clamp(
                        player.getXRot(),
                        -90.0F,
                        90.0F
                );

        LOGGER.info("polled yaw={} pitch={}", yaw, pitch); // TEMP

        possession.setRotation(
                yaw,
                pitch
        );

        /*
         * Keep the player's own head aligned, since the spectator camera
         * originates from this player's client.
         */
        player.setYHeadRot(yaw);
    }

    // ========================================================================
    // START POSSESSION
    // ========================================================================

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

        if (POSSESSED_ENTITY_IDS.contains(
                livingTarget.getUUID()
        )) {
            player.sendSystemMessage(Component.literal(
                    "That entity is already being controlled."
            ));
            return;
        }

        GameType previousMode =
                player.gameMode.getGameModeForPlayer();

        boolean previousNoAi =
                livingTarget instanceof Mob mob
                        && mob.isNoAi();

        Possession possession =
                new Possession(
                        id,
                        livingTarget,
                        previousMode,
                        previousNoAi
                );

        ACTIVE.put(
                id,
                possession
        );

        POSSESSED_ENTITY_IDS.add(
                livingTarget.getUUID()
        );

        /*
         * Disable mob AI.
         */
        if (livingTarget instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setTarget(null);
            mob.setAggressive(false);
        }

        /*
         * Spectator allows the player camera to follow the entity.
         */
        player.gameMode.changeGameModeForPlayer(
                GameType.SPECTATOR
        );

        /*
         * Set the camera to the possessed entity.
         */
        player.connection.send(
                new ClientboundSetCameraPacket(
                        livingTarget
                )
        );

        Vec3 eyePos = livingTarget.getEyePosition();
        player.teleportTo(
                (ServerLevel) livingTarget.level(),
                eyePos.x, eyePos.y, eyePos.z,
                Set.of(),
                possession.yaw(),
                possession.pitch(),
                false
        );

        /*
         * Start with the entity's current rotation.
         */
        possession.setRotation(
                livingTarget.getYRot(),
                livingTarget.getXRot()
        );

        applyRotation(
                possession,
                livingTarget
        );

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

    // ========================================================================
    // STOP
    // ========================================================================

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

            release(
                    player,
                    possession
            );

            player.sendSystemMessage(Component.literal(
                    "Released control of "
                            + possession.target()
                            .getName()
                            .getString()
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

    // ========================================================================
    // RELEASE
    // ========================================================================

    private static void release(
            ServerPlayer player,
            Possession possession
    ) {
        LivingEntity target =
                possession.target();

        if (target.isAlive()
                && target instanceof Mob mob) {

            mob.setNoAi(
                    possession.previousNoAi()
            );

            mob.setTarget(null);
        }

        /*
         * Put camera back on player.
         */
        player.connection.send(
                new ClientboundSetCameraPacket(
                        player
                )
        );

        /*
         * Restore original gamemode.
         */
        player.gameMode.changeGameModeForPlayer(
                possession.previousMode()
        );

        player.setDeltaMovement(
                Vec3.ZERO
        );
    }

    // ========================================================================
    // DISCONNECT
    // ========================================================================

    public static void handleDisconnect(
            ServerPlayer player
    ) {
        UUID playerId =
                player.getUUID();

        AWAITING_TARGET.remove(
                playerId
        );

        Possession possession =
                ACTIVE.remove(
                        playerId
                );

        if (possession == null) {
            return;
        }

        LivingEntity target =
                possession.target();

        POSSESSED_ENTITY_IDS.remove(
                target.getUUID()
        );

        if (target.isAlive()
                && target instanceof Mob mob) {

            mob.setNoAi(
                    possession.previousNoAi()
            );

            mob.setTarget(null);
        }
    }

    // ========================================================================
    // SHUTDOWN
    // ========================================================================

    /** Called from ServerLifecycleEvents.SERVER_STOPPED. */
    public static void clearAll() {
        AWAITING_TARGET.clear();
        ACTIVE.clear();
        POSSESSED_ENTITY_IDS.clear();
    }

    // ========================================================================
    // SERVER TICK
    // ========================================================================

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
            if (player == null
                    || !target.isAlive()) {

                if (player != null) {
                    release(
                            player,
                            possession
                    );
                }

                POSSESSED_ENTITY_IDS.remove(
                        target.getUUID()
                );

                iterator.remove();

                continue;
            }

            /*
             * Keep the actual player stationary.
             */
            player.setDeltaMovement(
                    Vec3.ZERO
            );

            /*
             * Pull this tick's keyboard and mouse state off the player.
             */
            pollControls(
                    player,
                    possession
            );

            /*
             * Apply camera rotation to the entity.
             */
            applyRotation(
                    possession,
                    target
            );

            /*
             * Force-sync rotation to all clients every tick.
             * This ensures spectator camera moves instantly with mouse input.
             */
            syncRotationToClients(
                    target
            );

            /*
             * Apply WASD movement.
             */
            updateMovement(
                    possession,
                    target
            );

            /*
             * Stop hostile mobs targeting the possessed entity.
             */
            protectFromHostiles(
                    target
            );
        }
    }

    // ========================================================================
    // ROTATION
    // ========================================================================

    /**
     * Sync the entity's rotation to all clients tracking it.
     * This ensures the spectator camera immediately reflects mouse input.
     */
private static void syncRotationToClients(LivingEntity target) {
    if (!(target.level() instanceof ServerLevel serverLevel)) {
        return;
    }

    byte headYaw = (byte) ((int) (target.getYHeadRot() * 256.0F / 360.0F) & 255);

    var headPacket = new net.minecraft.network.protocol.game.ClientboundRotateHeadPacket(
            target,
            headYaw
    );
    serverLevel.getServer().getPlayerList().broadcastAll(headPacket);

    // NEW: force-sync body yaw + pitch every tick, same reasoning as the head packet.
    byte packedYRot = (byte) Mth.floor(target.getYRot() * 256.0F / 360.0F);
    byte packedXRot = (byte) Mth.floor(target.getXRot() * 256.0F / 360.0F);

    var rotPacket = new net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Rot(
            target.getId(),
            packedYRot,
            packedXRot,
            target.onGround()
    );
    serverLevel.getServer().getPlayerList().broadcastAll(rotPacket);
}

    private static void applyRotation(
            Possession possession,
            LivingEntity target
    ) {
        float yaw =
                possession.yaw();

        float pitch =
                possession.pitch();

        pitch = Math.max(
                -90.0F,
                Math.min(
                        90.0F,
                        pitch
                )
        );

        /*
         * Main body rotation.
         */
        target.setYRot(yaw);

        /*
         * Head rotation.
         */
        target.setYHeadRot(yaw);

        /*
         * Looking up/down.
         */
        target.setXRot(pitch);

        /*
         * Mobs have a separate body rotation.
         */
        if (target instanceof Mob mob) {
            mob.setYBodyRot(yaw);
        }
    }

    // ========================================================================
    // MOVEMENT
    // ========================================================================

    private static void updateMovement(
            Possession possession,
            LivingEntity target
    ) {
        double forward = 0.0;
        double strafe = 0.0;

        /*
         * W
         */
        if (possession.forward()) {
            forward += 1.0;
        }

        /*
         * S
         */
        if (possession.backward()) {
            forward -= 1.0;
        }

        /*
         * A
         */
        if (possession.left()) {
            strafe += 1.0;
        }

        /*
         * D
         */
        if (possession.right()) {
            strafe -= 1.0;
        }

        /*
         * Speed.
         */
        double speed =
                WALK_SPEED;

        if (possession.sneaking()) {
            speed *= 0.3;
        }

        if (possession.sprinting()) {
            speed =
                    SPRINT_SPEED;
        }

        /*
         * Convert movement to world coordinates using
         * the possessed entity's yaw.
         */
        double yaw =
                Math.toRadians(
                        possession.yaw()
                );

        double sin =
                Math.sin(yaw);

        double cos =
                Math.cos(yaw);

        double moveX =
                -sin * forward;

        double moveZ =
                cos * forward;

        moveX +=
                cos * strafe;

        moveZ +=
                sin * strafe;

        /*
         * Normalize diagonal movement.
         */
        double length =
                Math.sqrt(
                        moveX * moveX
                                + moveZ * moveZ
                );

        if (length > 1.0E-4) {

            moveX /=
                    length;

            moveZ /=
                    length;

        } else {

            moveX = 0.0;
            moveZ = 0.0;
        }

        /*
         * Vertical velocity.
         */
        double verticalVelocity =
                target.getDeltaMovement().y;

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

            verticalVelocity -=
                    GRAVITY;
        }

        /*
         * Maximum falling velocity.
         */
        if (verticalVelocity < -3.92) {
            verticalVelocity =
                    -3.92;
        }

        /*
         * Horizontal velocity.
         */
        double velocityX =
                moveX * speed;

        double velocityZ =
                moveZ * speed;

        /*
         * Friction when no movement key is held.
         */
        if (forward == 0.0
                && strafe == 0.0) {

            velocityX *= 0.65;
            velocityZ *= 0.65;
        }

        Vec3 movement =
                new Vec3(
                        velocityX,
                        verticalVelocity,
                        velocityZ
                );

        target.setDeltaMovement(
                movement
        );

        /*
         * Vanilla collision movement.
         */
        target.move(
                MoverType.SELF,
                movement
        );
    }

    // ========================================================================
    // HOSTILE PROTECTION
    // ========================================================================

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
        ).forEach(
                mob -> mob.setTarget(null)
        );
    }

    // ========================================================================
    // POSSESSION DATA
    // ========================================================================

    private static final class Possession {

        private final UUID playerId;

        private final LivingEntity target;

        private final GameType previousMode;

        private final boolean previousNoAi;

        /*
         * Keyboard input.
         */
        private boolean forward;
        private boolean backward;
        private boolean left;
        private boolean right;
        private boolean jumping;
        private boolean sneaking;
        private boolean sprinting;

        /*
         * Camera rotation.
         */
        private float yaw;
        private float pitch;

        private Possession(
                UUID playerId,
                LivingEntity target,
                GameType previousMode,
                boolean previousNoAi
        ) {
            this.playerId =
                    playerId;

            this.target =
                    target;

            this.previousMode =
                    previousMode;

            this.previousNoAi =
                    previousNoAi;

            this.yaw =
                    target.getYRot();

            this.pitch =
                    target.getXRot();
        }

        // --------------------------------------------------------------------
        // ENTITY
        // --------------------------------------------------------------------

        private LivingEntity target() {
            return target;
        }

        private GameType previousMode() {
            return previousMode;
        }

        private boolean previousNoAi() {
            return previousNoAi;
        }

        // --------------------------------------------------------------------
        // INPUT
        // --------------------------------------------------------------------

        private void setInput(
                boolean forward,
                boolean backward,
                boolean left,
                boolean right,
                boolean jumping,
                boolean sneaking,
                boolean sprinting
        ) {
            this.forward =
                    forward;

            this.backward =
                    backward;

            this.left =
                    left;

            this.right =
                    right;

            this.jumping =
                    jumping;

            this.sneaking =
                    sneaking;

            this.sprinting =
                    sprinting;
        }

        private boolean forward() {
            return forward;
        }

        private boolean backward() {
            return backward;
        }

        private boolean left() {
            return left;
        }

        private boolean right() {
            return right;
        }

        private boolean jumping() {
            return jumping;
        }

        private boolean sneaking() {
            return sneaking;
        }

        private boolean sprinting() {
            return sprinting;
        }

        // --------------------------------------------------------------------
        // ROTATION
        // --------------------------------------------------------------------

        private void setRotation(
                float yaw,
                float pitch
        ) {
            this.yaw =
                    yaw;

            this.pitch =
                    pitch;
        }

        private float yaw() {
            return yaw;
        }

        private float pitch() {
            return pitch;
        }

        // --------------------------------------------------------------------
        // PLAYER
        // --------------------------------------------------------------------

        private ServerPlayer resolvePlayer() {

            if (target.level()
                    instanceof ServerLevel serverLevel) {

                return serverLevel
                        .getServer()
                        .getPlayerList()
                        .getPlayer(
                                playerId
                        );
            }

            return null;
        }
    }
}