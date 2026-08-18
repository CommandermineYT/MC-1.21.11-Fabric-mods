package com.totalecollapse;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.Command;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DoubleArgumentType; // FIXED: argument -> arguments
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public class TotaleCollapse implements ModInitializer {
    public static final String MOD_ID = "totale-collapse";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final List<FallingBlockEntity> ACTIVE_METEORS = new ArrayList<>();
    private static final Random RANDOM = new Random();

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                Commands.literal("collapse")
                    .then(Commands.literal("meteor")
                        .executes(context -> {
                            ServerLevel level = context.getSource().getLevel();
                            Vec3 origin = context.getSource().getPosition();

                            BlockPos spawnPos = new BlockPos(
                                (int)origin.x,
                                (int)(origin.y + 30.0),
                                (int)origin.z
                            );

                            FallingBlockEntity meteor = FallingBlockEntity.fall(
                                level,
                                spawnPos,
                                Blocks.MAGMA_BLOCK.defaultBlockState()
                            );

                            meteor.setDeltaMovement(0.35, -0.80, 0.35);
                            meteor.dropItem = false;

                            ACTIVE_METEORS.add(meteor);

                            context.getSource().sendSuccess(
                                () -> Component.literal("Total collapse initiated"),
                                true
                            );

                            return Command.SINGLE_SUCCESS;
                        })
                    )
                    .then(Commands.literal("meteor_shower")
                        .then(Commands.argument("x", DoubleArgumentType.doubleArg())
                            .then(Commands.argument("y", DoubleArgumentType.doubleArg())
                                .then(Commands.argument("z", DoubleArgumentType.doubleArg())
                                    .executes(context -> {
                                        ServerLevel level = context.getSource().getLevel();
                                        double x = context.getArgument("x", Double.class);
                                        double y = context.getArgument("y", Double.class);
                                        double z = context.getArgument("z", Double.class);

                                        BlockPos spawnPos = new BlockPos(
                                            (int)x,
                                            (int)(y + 30.0),
                                            (int)z
                                        );

                                        for (int i = 0; i < 15; i++) {
                                            FallingBlockEntity meteor = FallingBlockEntity.fall(
                                                level,
                                                new BlockPos(
                                                    spawnPos.getX() + (int)((RANDOM.nextDouble() - 0.5) * 20), // FIXED: cast to int
                                                    spawnPos.getY(),
                                                    spawnPos.getZ() + (int)((RANDOM.nextDouble() - 0.5) * 20)  // FIXED: cast to int
                                                ),
                                                Blocks.MAGMA_BLOCK.defaultBlockState()
                                            );

                                            meteor.setDeltaMovement(
                                                (RANDOM.nextDouble() - 0.5) * 0.4,
                                                -0.80 - RANDOM.nextDouble() * 0.2,
                                                (RANDOM.nextDouble() - 0.5) * 0.4
                                            );
                                            meteor.dropItem = false;

                                            ACTIVE_METEORS.add(meteor);
                                        }

                                        context.getSource().sendSuccess(
                                            () -> Component.literal("Meteor shower initiated at " + x + ", " + y + ", " + z),
                                            true
                                        );

                                        return Command.SINGLE_SUCCESS;
                                    })
                                )
                            )
                        )
                    )
            );
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> tickMeteors());
    }

    private static void tickMeteors() {
        Iterator<FallingBlockEntity> iterator = ACTIVE_METEORS.iterator();

        while (iterator.hasNext()) {
            FallingBlockEntity meteor = iterator.next();

            if (!meteor.isAlive()) {
                spawnImpactBurst((ServerLevel) meteor.level(), meteor.position());
                iterator.remove();
                continue;
            }

            ServerLevel level = (ServerLevel) meteor.level();
            Vec3 position = meteor.position();

            level.sendParticles(
                ParticleTypes.FLAME,
                position.x,
                position.y,
                position.z,
                6,
                0.25,
                0.25,
                0.25,
                0.02
            );

            level.sendParticles(
                ParticleTypes.LARGE_SMOKE,
                position.x,
                position.y,
                position.z,
                4,
                0.3,
                0.3,
                0.3,
                0.01
            );
        }
    }

    private static void spawnImpactBurst(ServerLevel level, Vec3 position) {
        level.sendParticles(
            ParticleTypes.EXPLOSION_EMITTER,
            position.x,
            position.y,
            position.z,
            1,
            0.0,
            0.0,
            0.0,
            0.0
        );

        level.sendParticles(
            ParticleTypes.FLAME,
            position.x,
            position.y,
            position.z,
            30,
            1.2,
            0.4,
            1.2,
            0.12
        );

        level.sendParticles(
            ParticleTypes.LARGE_SMOKE,
            position.x,
            position.y,
            position.z,
            20,
            1.0,
            0.5,
            1.0,
            0.08
        );
    }
}
