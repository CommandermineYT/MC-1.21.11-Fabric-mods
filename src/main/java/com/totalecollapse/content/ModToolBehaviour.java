package com.totalecollapse.content;

import com.totalecollapse.MindControlManager;
import com.totalecollapse.TotaleCollapse;

import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Makes the special tools do what the commands do, so the features are reachable without typing.
 * The commands still work -- these are an addition, not a replacement.
 */
public final class ModToolBehaviour {

    private ModToolBehaviour() {
    }

    public static void init() {
        // Right-clicking a block with the staff targets that exact block.
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }

            ItemStack held = player.getItemInHand(hand);

            if (held.is(ModTools.METEOR_STAFF)) {
                TotaleCollapse.castMeteorGroup((ServerLevel) level, hitResult.getLocation());
                return InteractionResult.CONSUME;
            }

            if (held.is(ModTools.MIND_SHARD)) {
                return armMindShard(player);
            }

            return InteractionResult.PASS;
        });

        // Right-clicking air with the shard should also arm it, otherwise the player has to be
        // looking at a block to use it, which is awkward when the target is a flying mob.
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (level.isClientSide() || hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }

            if (player.getItemInHand(hand).is(ModTools.MIND_SHARD)) {
                return armMindShard(player);
            }

            return InteractionResult.PASS;
        });
    }

    private static InteractionResult armMindShard(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.PASS;
        }

        MindControlManager.beginAwaitingTarget(serverPlayer);
        serverPlayer.displayClientMessage(
                Component.translatable("message.totale-collapse.mind_shard_armed"), true);

        return InteractionResult.CONSUME;
    }
}
