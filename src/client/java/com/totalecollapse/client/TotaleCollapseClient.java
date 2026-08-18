package com.totalecollapse.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random; // Import Random

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.phys.Vec3;

public class TotaleCollapseClient implements ClientModInitializer {
    private static final int METEOR_COUNT = 35;
    private static final double MIN_RADIUS = 4.0;
    private static final double MAX_RADIUS = 50.0;
    private static final double START_HEIGHT = 30.0;
    private static final double MIN_ANGLE = 40.0;
    private static final double MAX_ANGLE = 50.0;
    private static final double SPEED = 0.75;

    private static final Random RANDOM = new Random(); // Declare the Random instance
    private static final List<Meteor> METEORS = new ArrayList<>();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(TotaleCollapseClient::tickMeteors);
        // Call createMeteorShower here to ensure it's used
        createMeteorShower();
    }

    private static void createMeteorShower() { // This method is now used
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft.player == null) {
            return;
        }

        Vec3 playerPosition = minecraft.player.position();

        for (int i = 0; i < METEOR_COUNT; i++) {
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

            meteor.position = meteor.position.add(meteor.velocity);

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

        for (int i = 0; i < 12; i++) {
            double velocityX = (RANDOM.nextDouble() - 0.5) * 0.35;
            double velocityY = RANDOM.nextDouble() * 0.25;
            double velocityZ = (RANDOM.nextDouble() - 0.5) * 0.35;

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

    private static class Meteor {
        private Vec3 position;
        private final Vec3 velocity;
        private final Vec3 target;

        private Meteor(Vec3 position, Vec3 velocity, Vec3 target) {
            this.position = position;
            this.velocity = velocity;
            this.target = target;
        }
    }
}

