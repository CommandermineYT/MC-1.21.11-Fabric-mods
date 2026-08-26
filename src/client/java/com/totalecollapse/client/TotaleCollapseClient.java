package com.totalecollapse.client;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;


public class TotaleCollapseClient implements ClientModInitializer {

    private static final String PRINTER_PREFIX = "#PrinterV2";
    private static boolean selectionMode = false;
    private static BlockPos pos1 = null;
    private static BlockPos pos2 = null;

    private static final int METEOR_COUNT = 35;
    private static final int MAX_ACTIVE_METEORS = 200;
    private static final double MIN_RADIUS = 4.0;
    private static final double MAX_RADIUS = 50.0;
    private static final double START_HEIGHT = 30.0;
    private static final double MIN_ANGLE = 40.0;
    private static final double MAX_ANGLE = 50.0;
    private static final double SPEED = 0.75;

    private static final int IMPACT_PARTICLE_COUNT = 12;
    private static final double IMPACT_SPREAD = 0.35;
    private static final double IMPACT_LIFT = 0.25;

    private static final Random RANDOM = new Random();
    private static final List<Meteor> METEORS = new ArrayList<>();

    private static boolean showerQueued = false;

    @Override
    public void onInitializeClient() {
        HudRenderCallback.EVENT.register((graphics, tickDelta) -> Logo.render(graphics));
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());

        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            if (!message.startsWith(PRINTER_PREFIX)) {
                return true;
            }

            handleCommand(message);
            return false;
        });

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!selectionMode || player == null || world == null || !world.isClientSide()) {
                return InteractionResult.PASS;
            }

            if (hand != InteractionHand.MAIN_HAND && hand != InteractionHand.OFF_HAND) {
                return InteractionResult.PASS;
            }

            selectBlock(hitResult);
            return InteractionResult.SUCCESS;
        });

        // World render events may not be available on all mappings/versions;
        // skip registering the world render handler when unavailable.
    }



    private static void handleCommand(String message) {
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return;
        }

        if (!trimmed.regionMatches(true, 0, PRINTER_PREFIX, 0, PRINTER_PREFIX.length())) {
            return;
        }

        String[] parts = trimmed.split("\\s+");
        if (parts.length <= 1 || "help".equalsIgnoreCase(parts[1])) {
            printHelp();
            return;
        }

        switch (parts[1].toLowerCase(Locale.ROOT)) {
            case "select" -> {
                selectionMode = true;
                sendMessage("Selection mode enabled. Right-click two blocks to set Pos1 and Pos2.");
            }
            case "stop" -> {
                selectionMode = false;
                pos1 = null;
                pos2 = null;
                sendMessage("Printer selection cleared.");
            }
            default -> printHelp();
        }
    }

    private static void printHelp() {
        sendMessage("PrinterV2 commands:");
        sendMessage("  #PrinterV2 help - show this menu");
        sendMessage("  #PrinterV2 Select - enable block selection mode");
        sendMessage("  #PrinterV2 Stop - clear all current selections");
    }

    private static void sendMessage(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        minecraft.player.displayClientMessage(Component.literal(text), false);
    }

    private static void selectBlock(BlockHitResult hitResult) {
        BlockPos blockPos = hitResult.getBlockPos();

        if (pos1 == null) {
            pos1 = blockPos;
            sendMessage("Pos1 set to " + blockPos);
            return;
        }

        if (pos2 == null) {
            pos2 = blockPos;
            sendMessage("Pos2 set to " + blockPos + ". Area highlighted.");
            return;
        }

        pos1 = blockPos;
        pos2 = null;
        sendMessage("Pos1 set to " + blockPos + ". Pos2 reset.");
    }

    public static void startMeteorShower() {
        showerQueued = true;
    }
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