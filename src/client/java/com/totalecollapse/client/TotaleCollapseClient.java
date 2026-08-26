package com.totalecollapse.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side initializer for the Totale Collapse mod.
 * Handles rendering of the HUD logo and an optional meteor shower
 * particle effect that can be triggered around the player.
 */
public class TotaleCollapseClient implements ClientModInitializer {

    // ---- Meteor shower tuning ----
    private static final int METEOR_COUNT = 35;
    private static final int MAX_ACTIVE_METEORS = 200; // safety cap so the list can't grow unbounded
    private static final double MIN_RADIUS = 4.0;
    private static final double MAX_RADIUS = 50.0;
    private static final double START_HEIGHT = 30.0;
    private static final double MIN_ANGLE = 40.0;
    private static final double MAX_ANGLE = 50.0;
    private static final double SPEED = 0.75;

    // ---- Impact particle tuning ----
    private static final int IMPACT_PARTICLE_COUNT = 12;
    private static final double IMPACT_SPREAD = 0.35;
    private static final double IMPACT_LIFT = 0.25;

    private static final Random RANDOM = new Random();
    private static final List<Meteor> METEORS = new ArrayList<>();

    /** Set true to spawn a fresh burst of meteors on the next tick. */
    private static boolean showerQueued = false;

@Override
public void onInitializeClient() {
    HudRenderCallback.EVENT.register((graphics, tickDelta) -> Logo.render(graphics));
    HudRenderCallback.EVENT.register((graphics, tickDelta) -> tick());
}

    /**
     * Public entry point for triggering a meteor shower, e.g. from a
     * command or key binding. Safe to call even if a shower is already
     * in progress; it will simply queue another burst once the current
     * one finishes.
     */
    public static void startMeteorShower() {
        showerQueued = true;
    }

    /**
     * Runs once per frame: spawns a queued shower if one was requested,
     * then advances whatever meteors are currently in flight.
     */
    private static void tick() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.level == null || minecraft.player == null) {
            // No world loaded (e.g. on the main menu) - drop any stale state.
            METEORS.clear();
            showerQueued = false;
            return;
        }

        if (showerQueued) {
            createMeteorShower();
            showerQueued = false;
        }

        tickMeteors(minecraft);
    }

    private static void createMeteorShower() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null) {
            return;
        }

        Vec3 playerPosition = minecraft.player.position();

        // Never spawn past the safety cap, even if a previous batch hasn't
        // fully landed yet.
        int spawnCount = Math.min(METEOR_COUNT, MAX_ACTIVE_METEORS - METEORS.size());

        for (int i = 0; i < spawnCount; i++) {
            double direction = RANDOM.nextDouble() * Math.PI * 2.0;
            double distance = MIN_RADIUS + RANDOM.nextDouble() * (MAX_RADIUS - MIN_RADIUS);

            double targetX = playerPosition.x + Math.cos(direction) * distance;
            double targetZ = playerPosition.z + Math.sin(direction) * distance;

            Vec3 start = new Vec3(
                playerPosition.x,
                playerPosition.y + START_HEIGHT,
                playerPosition.z
            );

            Vec3 target = new Vec3(targetX, playerPosition.y, targetZ);

            Vec3 horizontalDirection = new Vec3(
                target.x - start.x,
                0.0,
                target.z - start.z
            ).normalize();

            double angle = Math.toRadians(
                MIN_ANGLE + RANDOM.nextDouble() * (MAX_ANGLE - MIN_ANGLE)
            );

            double horizontalSpeed = SPEED * Math.cos(angle);
            double downwardSpeed = SPEED * Math.sin(angle);

            Vec3 velocity = new Vec3(
                horizontalDirection.x * horizontalSpeed,
                -downwardSpeed,
                horizontalDirection.z * horizontalSpeed
            );

            METEORS.add(new Meteor(start, velocity, target));
        }
    }

    private static void tickMeteors(Minecraft minecraft) {
        ClientLevel level = minecraft.level;

        if (level == null) {
            METEORS.clear();
            return;
        }

        Iterator<Meteor> iterator = METEORS.iterator();

        while (iterator.hasNext()) {
            Meteor meteor = iterator.next();

            meteor.advance();

            level.addParticle(
                ParticleTypes.FLAME,
                meteor.position.x,
                meteor.position.y,
                meteor.position.z,
                0.0,
                0.0,
                0.0
            );

            level.addParticle(
                ParticleTypes.SMOKE,
                meteor.position.x,
                meteor.position.y,
                meteor.position.z,
                0.0,
                0.02,
                0.0
            );

            if (meteor.position.y <= meteor.target.y) {
                createImpact(level, meteor.target);
                iterator.remove();
            }
        }
    }

    private static void createImpact(ClientLevel level, Vec3 position) {
        level.addParticle(
            ParticleTypes.EXPLOSION_EMITTER,
            position.x,
            position.y,
            position.z,
            0.0,
            0.0,
            0.0
        );

        for (int i = 0; i < IMPACT_PARTICLE_COUNT; i++) {
            double velocityX = (RANDOM.nextDouble() - 0.5) * IMPACT_SPREAD;
            double velocityY = RANDOM.nextDouble() * IMPACT_LIFT;
            double velocityZ = (RANDOM.nextDouble() - 0.5) * IMPACT_SPREAD;

            level.addParticle(
                ParticleTypes.FLAME,
                position.x,
                position.y,
                position.z,
                velocityX,
                velocityY,
                velocityZ
            );

            level.addParticle(
                ParticleTypes.SMOKE,
                position.x,
                position.y,
                position.z,
                velocityX,
                velocityY,
                velocityZ
            );
        }
    }

    /**
     * A single falling meteor: current position, constant per-tick
     * velocity, and the ground-level target position that ends its flight.
     */
    private static class Meteor {
        private Vec3 position;
        private final Vec3 velocity;
        private final Vec3 target;

        private Meteor(Vec3 position, Vec3 velocity, Vec3 target) {
            this.position = position;
            this.velocity = velocity;
            this.target = target;
        }

        private void advance() {
            position = position.add(velocity);
        }
    }
}