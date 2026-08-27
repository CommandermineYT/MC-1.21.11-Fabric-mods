package com.totalecollapse.mixin;

import com.totalecollapse.MindControlManager;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerMixin {

    /**
     * WASD / jump / sneak / sprint.
     */
    @Inject(
            method = "handlePlayerInput",
            at = @At("HEAD")
    )
    private void totaleCollapse$handlePlayerInput(
            ServerboundPlayerInputPacket packet,
            CallbackInfo ci
    ) {
        ServerGamePacketListenerImpl connection =
                (ServerGamePacketListenerImpl) (Object) this;

        MindControlManager.handleInputPacket(
                connection.player,
                packet
        );
    }

    /**
     * Mouse movement / camera rotation.
     */
    @Inject(
            method = "handleMovePlayer",
            at = @At("HEAD")
    )
    private void totaleCollapse$handleMovePlayer(
            ServerboundMovePlayerPacket packet,
            CallbackInfo ci
    ) {
        ServerGamePacketListenerImpl connection =
                (ServerGamePacketListenerImpl) (Object) this;

        MindControlManager.handleRotationPacket(
                connection.player,
                packet
        );
    }
}