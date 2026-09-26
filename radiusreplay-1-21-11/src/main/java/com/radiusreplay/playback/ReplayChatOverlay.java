package com.radiusreplay.playback;

import com.radiusreplay.record.ReplayFile;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The replay chat overlay: recorded messages appear at (approximately) the
 * same position in the timeline as when they were captured, then fade with
 * the classic chat lifetime. Fully client-side and local to the replay —
 * nothing is ever sent to any server. The viewer can scroll back through the
 * session with the mouse wheel while the overlay is visible, and the normal
 * live chat stays completely untouched.
 */
public final class ReplayChatOverlay {
    /** Chat lifetime in ticks (matches vanilla's on-screen hold). */
    private static final int LIFE_TICKS = 200;

    private static final class Line {
        final boolean isPlayer;
        final String sender;
        final String text;
        final int bornAt;

        Line(boolean isPlayer, String sender, String text, int bornAt) {
            this.isPlayer = isPlayer;
            this.sender = sender;
            this.text = text;
            this.bornAt = bornAt;
        }
    }

    private static final ArrayDeque<Line> live = new ArrayDeque<>();
    /** Full session history, newest last, for scroll-back. */
    private static final List<ReplayFile.Chat> history = new ArrayList<>();
    private static boolean visible = true;
    private static int scrollOffset;

    private ReplayChatOverlay() {}

    public static void register() {
        HudRenderCallback.EVENT.register(ReplayChatOverlay::render);
    }

    public static void clear() {
        live.clear();
        history.clear();
        scrollOffset = 0;
    }

    public static void toggleVisible() {
        visible = !visible;
    }

    public static void setVisible(boolean value) { visible = value; }

    public static boolean isVisible() {
        return visible;
    }

    public static void scroll(int lines) {
        scrollOffset = Math.max(0, Math.min(history.size(), scrollOffset + lines));
    }

    public static int historySize() {
        return history.size();
    }

    /** Called by the playback engine for each recorded message at its tick. */
    public static void push(ReplayFile.Chat chat) {
        history.add(chat);
        // Keep the viewer pinned near the newest message: follow the stream
        // as it grows, but never scroll past the newest line.
        scrollOffset = Math.max(0, Math.min(history.size() - 1, scrollOffset - 1));
        live.addLast(new Line(chat.isPlayer(), chat.sender(), chat.text(),
                (int) PlaybackEngine.playhead()));
        while (live.size() > 80) {
            live.removeFirst();
        }
    }

    private static void render(GuiGraphics g, net.minecraft.client.DeltaTracker tracker) {
        if (!visible || !ReplaySettings.chat()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        if (font == null) {
            return;
        }
        int now = (int) PlaybackEngine.playhead();
        int screenH = g.guiHeight();
        int y = screenH - 44;
        int maxTextWidth = Math.min(320, g.guiWidth() - 24);

        Iterator<Line> it = live.descendingIterator();
        while (it.hasNext()) {
            Line line = it.next();
            int age = now - line.bornAt;
            if (age > LIFE_TICKS) {
                continue;
            }
            float alpha = age > LIFE_TICKS - 20 ? (LIFE_TICKS - age) / 20f : 1f;
            String text = line.isPlayer
                    ? "<" + line.sender + "> " + line.text
                    : line.text;
            // Trim to width cheaply (plainSubstrByWidth returns a String).
            text = font.plainSubstrByWidth(text, maxTextWidth - 8);
            int color = line.isPlayer ? 0xFFFFFF : 0xC8C8C8;
            int bg = ((int) (alpha * 100)) << 24;
            int fg = line.isPlayer
                    ? applyAlpha(color, alpha)
                    : applyAlpha(color, alpha);
            g.fill(2, y - 9, 4 + font.width(text) + 4, y + 1, bg);
            g.drawString(font, text, 4, y, fg);
            y -= 10;
            if (y < screenH / 3) {
                break;
            }
        }
    }

    private static int applyAlpha(int rgb, float alpha) {
        int a = (int) (alpha * 255) & 0xFF;
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
