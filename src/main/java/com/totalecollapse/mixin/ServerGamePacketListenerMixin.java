package com.totalecollapse.mixin;

import com.totalecollapse.MindControlManager;

import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;

@Mixin(ServerGamePacketListenerImpl.class)
public class ServerGamePacketListenerMixin {

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
    
}
