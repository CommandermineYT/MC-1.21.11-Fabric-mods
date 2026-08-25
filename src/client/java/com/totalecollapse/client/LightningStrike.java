package com.totalecollapse.client;

import java.util.Random;
import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
 
import com.totalecollapse.TotaleCollapse;
import net.minecraft.world.phys.Vec3;

public final class LightningStrike {

    private static final KeyMapping.Category CATEGORY =
            KeyMapping.Category.register(Identifier.fromNamespaceAndPath("totalecollapse", "totalecollapse"));

    private static final String KEY_NAME = "key.totalecollapse.lightning_spell";

    private static final int STRIKE_COUNT = 6;
    private static final double MIN_RADIUS = 2.0;
    private static final double MAX_RADIUS = 12.0;
    private static final int STRIKE_DELAY_TICKS = 20; // initial delay in ticks (20 ticks = 1 second)
    private static final int STAGGER_TICKS = 5; // ticks between each staggered strike

    private static final Random RANDOM = new Random();

    private static KeyMapping lightningKey;

    private static final List<PendingStrike> scheduledStrikes = new ArrayList<>();

    private LightningStrike() {
    }

    public static void init() {
        lightningKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                KEY_NAME,
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_L,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(LightningStrike::onClientTick);
    }

    private static void onClientTick(Minecraft minecraft) {
        if (minecraft == null) return;
        while (lightningKey.consumeClick()) {
            castSpell(minecraft);
        }

        if (minecraft.level == null) {
            scheduledStrikes.clear();
            return;
        }

        Iterator<PendingStrike> it = scheduledStrikes.iterator();
        while (it.hasNext()) {
            PendingStrike ps = it.next();
            ps.ticksRemaining--;
            if (ps.ticksRemaining <= 0) {
                performStrike(minecraft, ps.target);
                it.remove();
            }
        }
    }

    private static void castSpell(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        var level = minecraft.level;
        if (player == null || player.connection == null || level == null) {
            return;
        }

        // request thunder/weather on server
        player.connection.sendCommand("weather thunder");

        Vec3 origin = player.position();

        for (int i = 0; i < STRIKE_COUNT; i++) {
            double angle = RANDOM.nextDouble() * Math.PI * 2.0;
            double distance = MIN_RADIUS + RANDOM.nextDouble() * (MAX_RADIUS - MIN_RADIUS);

            double x = origin.x + Math.cos(angle) * distance;
            double z = origin.z + Math.sin(angle) * distance;

            int groundY = 0;
            int startY = (int)Math.floor(origin.y);
            for (int y = startY; y >= 0; y--) {
                net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos((int)Math.floor(x), y, (int)Math.floor(z));
                    if (!level.getBlockState(pos).isAir()) {
                    groundY = y;
                    break;
                    
                }
                
            }
            double strikeY = groundY + 1.0;

            int ticks = STRIKE_DELAY_TICKS + i * STAGGER_TICKS;
            scheduledStrikes.add(new PendingStrike(new Vec3(x, strikeY, z), ticks));
        }
    }

    

    private static void performStrike(Minecraft minecraft, Vec3 target) {
        if (minecraft == null) return;
        LocalPlayer player = minecraft.player;
        if (player == null || player.connection == null || minecraft.level == null) {
            return;
        }

        // send a network packet to the server requesting an authoritative lightning summon
        var level = minecraft.level;
        var buf = PacketByteBufs.create();
        buf.writeDouble(target.x);
        buf.writeDouble(target.y);
        buf.writeDouble(target.z);
        try {
            try {
                java.lang.reflect.Method sendMethod = ClientPlayNetworking.class.getMethod("send", net.minecraft.resources.Identifier.class, net.minecraft.network.FriendlyByteBuf.class);
                sendMethod.invoke(null, TotaleCollapse.LIGHTNING_PACKET, buf);
            } catch (NoSuchMethodException e) {
                // Try alternate signature: send(CustomPacketPayload)
                boolean sent = false;
                try {
                    Class<?> payloadClass = Class.forName("net.fabricmc.fabric.api.networking.v1.CustomPacketPayload");
                    try {
                        java.lang.reflect.Method of = payloadClass.getMethod("of", net.minecraft.resources.Identifier.class, net.minecraft.network.FriendlyByteBuf.class);
                        Object payload = of.invoke(null, TotaleCollapse.LIGHTNING_PACKET, buf);
                        java.lang.reflect.Method sendMethod2 = ClientPlayNetworking.class.getMethod("send", payloadClass);
                        sendMethod2.invoke(null, payload);
                        sent = true;
                    } catch (NoSuchMethodException ns) {
                        try {
                            java.lang.reflect.Constructor<?> ctor = payloadClass.getConstructor(net.minecraft.resources.Identifier.class, net.minecraft.network.FriendlyByteBuf.class);
                            Object payload = ctor.newInstance(TotaleCollapse.LIGHTNING_PACKET, buf);
                            java.lang.reflect.Method sendMethod2 = ClientPlayNetworking.class.getMethod("send", payloadClass);
                            sendMethod2.invoke(null, payload);
                            sent = true;
                        } catch (Throwable ignore) { }
                    }
                } catch (ClassNotFoundException cnf) { }

                if (!sent) {
                    // Last resort: try to construct vanilla packet and send via connection
                    try {
                        Class<?> pktClass = Class.forName("net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket");
                        java.lang.reflect.Constructor<?> ctor = pktClass.getConstructor(net.minecraft.resources.Identifier.class, net.minecraft.network.FriendlyByteBuf.class);
                        Object pkt = ctor.newInstance(TotaleCollapse.LIGHTNING_PACKET, buf);
                        java.lang.reflect.Method sendPkt = player.connection.getClass().getMethod("send", Class.forName("net.minecraft.network.protocol.Packet"));
                        sendPkt.invoke(player.connection, pkt);
                    } catch (Throwable ignore) { }
                }
            }
        } catch (Throwable t) {
            // ignore failures to send; client visuals will still play
        }

        level.addParticle(net.minecraft.core.particles.ParticleTypes.EXPLOSION_EMITTER, target.x, target.y, target.z, 0.0, 0.0, 0.0);
        for (int i = 0; i < 12; i++) {
            double vx = (RANDOM.nextDouble() - 0.5) * 0.5;
            double vy = RANDOM.nextDouble() * 0.5;
            double vz = (RANDOM.nextDouble() - 0.5) * 0.5;
            level.addParticle(net.minecraft.core.particles.ParticleTypes.FLAME, target.x, target.y + 0.5, target.z, vx, vy, vz);
            level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE, target.x, target.y + 0.5, target.z, vx, vy, vz);
        }
    }

    private static final class PendingStrike {
        private final Vec3 target;
        private int ticksRemaining;

        private PendingStrike(Vec3 target, int ticksRemaining) {
            this.target = target;
            this.ticksRemaining = ticksRemaining;
        }
    }
}