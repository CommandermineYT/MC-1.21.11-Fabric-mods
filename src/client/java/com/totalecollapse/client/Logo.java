package com.totalecollapse.client;

import java.util.Objects;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public class Logo {
    private static final Component DEFAULT_TITLE =
            Objects.requireNonNull(Component.literal("TotaleCollapseV4"));

    private static final Component INIT_TITLE =
            Objects.requireNonNull(Component.literal("Total Collapse Initialized"));

    private static final long METEOR_DELAY_MS = 1500L;
    private static final long REVERT_DELAY_MS = 3000L;

    private static long sequenceStartTime = -1L;
    private static boolean meteorsTriggered = false;
    private static boolean hasAutoStarted = false;

    public static void triggerSequence() {
        sequenceStartTime = System.currentTimeMillis();
        meteorsTriggered = false;
    }

    public static void render(GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        
        if (mc.gui.getDebugOverlay().showDebugScreen()) {
            return;
        }
        if (!hasAutoStarted) {
            hasAutoStarted = true;
            triggerSequence();
        }

        Component title = DEFAULT_TITLE;
        if (sequenceStartTime >= 0) {
            long elapsed = System.currentTimeMillis() - sequenceStartTime;
            if (elapsed < REVERT_DELAY_MS) {
                title = INIT_TITLE;
            } else {
                sequenceStartTime = -1L;
                meteorsTriggered = false;
            }
            if (!meteorsTriggered && elapsed >= METEOR_DELAY_MS) {
                meteorsTriggered = true;
            }
        }

        Font font = mc.font;
        int x = 4;
        int y = 4;
        int mainColor = 0xFFFF0000;
        int outlineColor = 0xFF000000;

        graphics.drawString(font, title, x - 1, y, outlineColor, false);
        graphics.drawString(font, title, x + 1, y, outlineColor, false);
        graphics.drawString(font, title, x, y - 1, outlineColor, false);
        graphics.drawString(font, title, x, y + 1, outlineColor, false);
        graphics.drawString(font, title, x, y, mainColor, false);
    }
}