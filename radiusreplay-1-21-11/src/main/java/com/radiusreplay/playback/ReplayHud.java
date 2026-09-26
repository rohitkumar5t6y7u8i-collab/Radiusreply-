package com.radiusreplay.playback;

import com.radiusreplay.record.ReplayFile;
import com.radiusreplay.record.Recorder;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * The playback HUD: timeline bar with a scrubber, time readout, speed and
 * camera labels, plus the recording indicator when a session is live.
 * Everything is drawn with plain fills and text — no textures, no frame
 * buffers, no per-frame allocations beyond a couple of small strings — which
 * keeps it genuinely cheap on low-end devices.
 */
public final class ReplayHud {
    private static final int BAR_MARGIN = 8;
    private static final int BAR_HEIGHT = 5;

    /** True while the user drags the scrubber; suppresses auto-advance. */
    private static boolean scrubbing;
    private static int scrubTick = -1;

    private ReplayHud() {}

    public static void register() {
        HudRenderCallback.EVENT.register(ReplayHud::render);
    }

    public static void tick(Minecraft client) {
        if (!scrubbing) {
            scrubTick = -1;
        }
    }

    /** Click/drag from the timeline screen; tick is in absolute replay ticks. */
    public static void setScrub(int tick, boolean active) {
        scrubbing = active;
        if (active) {
            scrubTick = tick;
            PlaybackEngine.seek(tick);
        } else {
            scrubTick = -1;
        }
    }

    private static void render(GuiGraphics g, net.minecraft.client.DeltaTracker tracker) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !ReplaySettings.replayHud()) {
            return;
        }
        Font font = mc.font;
        int w = g.guiWidth();

        if (Recorder.isRecording()) {
            int secs = Recorder.elapsedTicks() / 20;
            String label = "● REC " + String.format("%d:%02d", secs / 60, secs % 60);
            g.drawString(font, label, w - font.width(label) - 6, 6, 0xFFFF5555);
            return;
        }

        if (!PlaybackEngine.isActive()) {
            return;
        }

        ReplayFile file = PlaybackEngine.file();
        int total = PlaybackEngine.durationTicks();
        int cur = (int) PlaybackEngine.playhead();

        // Timeline bar.
        int barW = w - BAR_MARGIN * 2;
        int barY = g.guiHeight() - 64;
        g.fill(BAR_MARGIN, barY, BAR_MARGIN + barW, barY + BAR_HEIGHT, 0x66000000);
        int curX = total > 0 ? BAR_MARGIN + (int) ((long) cur * barW / total) : BAR_MARGIN;
        g.fill(BAR_MARGIN, barY, curX, barY + BAR_HEIGHT, 0xFFFF5555);
        // Playhead knob.
        g.fill(curX - 1, barY - 2, curX + 1, barY + BAR_HEIGHT + 2, 0xFFFFFFFF);

        // Readouts.
        String time = format(cur) + " / " + format(total);
        g.drawString(font, time, BAR_MARGIN, barY - 12, 0xFFFFFFFF);
        String right = PlaybackEngine.speedLabel() + "x  ·  " + PlaybackEngine.cameraLabel()
                + "  ·  " + (PlaybackEngine.mode() == PlaybackEngine.Mode.PLAYING ? "Playing" : "Paused");
        g.drawString(font, right, w - font.width(right) - BAR_MARGIN, barY - 12, 0xFFAAAAAA);

        // Chat scroll hint.
        if (ReplayChatOverlay.historySize() > 0 && ReplayChatOverlay.isVisible()) {
            String hint = "Scroll chat: wheel · " + ReplayChatOverlay.historySize() + " msgs";
            g.drawString(font, hint, BAR_MARGIN, barY + 10, 0xFF888888);
        }
    }

    /** Shared time formatter (mm:ss) used by the HUD and the menu screen. */
    static String format(int ticks) {
        int secs = ticks / 20;
        return String.format("%d:%02d", secs / 60, secs % 60);
    }
}
