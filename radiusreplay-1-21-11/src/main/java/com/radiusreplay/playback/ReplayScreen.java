package com.radiusreplay.playback;

import com.radiusreplay.record.ReplayFile;
import com.radiusreplay.record.Recorder;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The replay menu: record toggle, file list (open / delete / rename),
 * playback transport (play-pause, stop, speed, camera, jump), a scrubber
 * row and a live inventory peek for the current tick. Plain widget layout,
 * no textures, mobile-friendly touch targets — everything runs client-side
 * only. 1.21.11 input handlers take MouseButtonEvent/KeyEvent records.
 */
public final class ReplayScreen extends Screen {
    private static final int COL = 16;
    private static final int BTN_W = 180;
    private static final int BTN_H = 20;
    private static final int GAP = 4;

    private List<Path> files = new ArrayList<>();
    private int selected = -1;
    private String status = "";

    public ReplayScreen() {
        super(Component.literal("RadiusReplay"));
    }

    @Override
    protected void init() {
        refreshFiles();
        int y = 40;

        addRenderableWidget(Button.builder(
                        Component.literal(Recorder.isRecording() ? "Stop & save recording" : "Start recording"),
                        b -> {
                            if (Recorder.isRecording()) {
                                Recorder.stopAndSave();
                                status = "Recording saved.";
                            } else if (PlaybackEngine.isActive()) {
                                status = "Stop playback first.";
                            } else if (Recorder.start(this.minecraft)) {
                                status = "Recording…";
                            } else {
                                status = "Cannot record now.";
                            }
                            rebuild();
                        })
                .bounds(COL, y, BTN_W, BTN_H).build());
        y += BTN_H + GAP;

        addRenderableWidget(Button.builder(Component.literal("Replay files"), b -> { refreshFiles(); rebuild(); })
                .bounds(COL, y, BTN_W, BTN_H).build());
        y += BTN_H + GAP;

        addRenderableWidget(Button.builder(Component.literal("Open selected"), b -> openSelected())
                .bounds(COL, y, BTN_W, BTN_H).build());
        y += BTN_H + GAP;

        addRenderableWidget(Button.builder(Component.literal("Delete selected"), b -> deleteSelected())
                .bounds(COL, y, BTN_W, BTN_H).build());
        y += BTN_H + GAP;

        addRenderableWidget(Button.builder(Component.literal("Rename selected"), b -> renameSelected())
                .bounds(COL, y, BTN_W, BTN_H).build());
        y += BTN_H + 8;

        // Transport row.
        addRenderableWidget(Button.builder(
                        Component.literal(PlaybackEngine.mode() == PlaybackEngine.Mode.PLAYING ? "Pause" : "Play"),
                        b -> { PlaybackEngine.toggle(); rebuild(); })
                .bounds(COL, y, 60, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("Stop"), b -> { PlaybackEngine.stop(); rebuild(); })
                .bounds(COL + 64, y, 50, BTN_H).build());
        addRenderableWidget(Button.builder(
                        Component.literal(PlaybackEngine.speedLabel() + "x"),
                        b -> { PlaybackEngine.cycleSpeed(); rebuild(); })
                .bounds(COL + 118, y, 62, BTN_H).build());
        y += BTN_H + GAP;

        addRenderableWidget(Button.builder(Component.literal("Camera: " + PlaybackEngine.cameraLabel()),
                        b -> { PlaybackEngine.cycleCamera(this.minecraft); rebuild(); })
                .bounds(COL, y, BTN_W, BTN_H).build());
        y += BTN_H + GAP;

        addRenderableWidget(Button.builder(Component.literal("◀ -10s"), b -> PlaybackEngine.jump(-200))
                .bounds(COL, y, 86, BTN_H).build());
        addRenderableWidget(Button.builder(Component.literal("+10s ▶"), b -> PlaybackEngine.jump(200))
                .bounds(COL + 94, y, 86, BTN_H).build());
        y += BTN_H + GAP;

        addRenderableWidget(Button.builder(Component.literal("Chat: " + on(ReplaySettings.chat())),
                b -> { ReplaySettings.toggleChat(); rebuild(); })
                .bounds(COL, y, BTN_W, BTN_H).build());
        y += BTN_H + GAP;
        addRenderableWidget(Button.builder(Component.literal("Replay HUD: " + on(ReplaySettings.replayHud())),
                b -> { ReplaySettings.toggleHud(); rebuild(); })
                .bounds(COL, y, BTN_W, BTN_H).build());
        y += BTN_H + GAP;
        addRenderableWidget(Button.builder(Component.literal("Inventory / hands: " + on(ReplaySettings.inventory())),
                b -> { ReplaySettings.toggleInventory(); rebuild(); })
                .bounds(COL, y, BTN_W, BTN_H).build());
        y += BTN_H + GAP;
        addRenderableWidget(Button.builder(Component.literal("Recorded GUI: " + on(ReplaySettings.gui())),
                b -> { ReplaySettings.toggleGui(); rebuild(); })
                .bounds(COL, y, BTN_W, BTN_H).build());
        y += BTN_H + GAP;
        addRenderableWidget(Button.builder(Component.literal("Cursor: " + on(ReplaySettings.cursor())),
                b -> { ReplaySettings.toggleCursor(); rebuild(); })
                .bounds(COL, y, BTN_W, BTN_H).build());
        y += BTN_H + GAP;
        addRenderableWidget(Button.builder(Component.literal("Crosshair: " + on(ReplaySettings.crosshair())),
                b -> { ReplaySettings.toggleCrosshair(); rebuild(); })
                .bounds(COL, y, BTN_W, BTN_H).build());
        y += BTN_H + GAP * 2;

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, this.height - 28, 200, BTN_H).build());
    }

    private static String on(boolean value) {
        return value ? "ON" : "OFF";
    }

    private void rebuild() {
        this.clearWidgets();
        this.init();
    }

    private void refreshFiles() {
        files = new ArrayList<>();
        try (var stream = Files.list(Recorder.modReplayDir())) {
            stream.filter(p -> p.getFileName().toString().endsWith(".radiusreplay"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .limit(12)
                    .forEach(files::add);
        } catch (IOException ignored) {
            // No directory yet: empty list is correct.
        }
        if (selected >= files.size()) {
            selected = -1;
        }
    }

    private void openSelected() {
        if (selected < 0 || selected >= files.size()) {
            status = "Pick a file first.";
            return;
        }
        try {
            ReplayFile rf = ReplayFile.read(files.get(selected));
            if (PlaybackEngine.load(rf)) {
                PlaybackEngine.play();
                status = "Playing " + files.get(selected).getFileName();
            }
            rebuild();
        } catch (Exception e) {
            status = "Corrupted replay file.";
            com.radiusreplay.RadiusReplayClient.LOGGER.warn("Open failed", e);
        }
    }

    private void deleteSelected() {
        if (selected < 0 || selected >= files.size()) {
            status = "Pick a file first.";
            return;
        }
        try {
            Files.deleteIfExists(files.get(selected));
            status = "Deleted.";
            refreshFiles();
        } catch (IOException e) {
            status = "Delete failed.";
        }
        rebuild();
    }

    private void renameSelected() {
        if (selected < 0 || selected >= files.size()) {
            status = "Pick a file first.";
            return;
        }
        Path src = files.get(selected);
        String name = src.getFileName().toString();
        String base = name.endsWith(".radiusreplay") ? name.substring(0, name.length() - ".radiusreplay".length()) : name;
        String target = base + " (renamed).radiusreplay";
        try {
            Files.move(src, src.resolveSibling(target));
            status = "Renamed to " + target;
            refreshFiles();
        } catch (IOException e) {
            status = "Rename failed.";
        }
        rebuild();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(this.font, this.title, this.width / 2, 16, 0xFFFFFF);
        g.drawString(this.font, "Files:", COL, 30, 0xFFAAAAAA);

        // File list.
        int y = 30;
        for (int i = 0; i < files.size() && y + 10 < this.height - 150; i++) {
            Path p = files.get(i);
            boolean sel = i == selected;
            g.drawString(this.font, p.getFileName().toString(), COL + 60, y + 10,
                    sel ? 0xFFFF5555 : 0xFFCCCCCC);
            y += 10;
        }

        // Status + timeline.
        int barY = this.height - 70;
        int total = PlaybackEngine.durationTicks();
        int cur = (int) PlaybackEngine.playhead();
        int barW = this.width - COL * 2;
        g.fill(COL, barY, COL + barW, barY + 5, 0x66000000);
        if (total > 0) {
            int cx = COL + (int) ((long) cur * barW / total);
            g.fill(COL, barY, cx, barY + 5, 0xFFFF5555);
            g.fill(cx - 1, barY - 2, cx + 1, barY + 7, 0xFFFFFFFF);
        }
        String time = PlaybackEngine.isActive()
                ? ReplayHud.format(cur) + " / " + ReplayHud.format(total)
                : (total > 0 ? ReplayHud.format(total) : "");
        g.drawString(this.font, time, COL, barY - 12, 0xFFFFFFFF);
        if (!status.isEmpty()) {
            g.drawString(this.font, status, COL, barY + 12, 0xFF55FF88);
        }

        // Inventory peek: hotbar of the recorded player at this tick, real
        // icons, counts and durability bars straight from the item table.
        int totalTicks = PlaybackEngine.durationTicks();
        if (PlaybackEngine.isActive() && totalTicks > 0) {
            int idx = (int) PlaybackEngine.playhead();
            ReplayFile.TickEntry e = PlaybackEngine.file().tickEntries.get(idx);
            if (e.playerInventory != null) {
                g.drawString(this.font, "Inventory (recorded):", COL, barY + 26, 0xFFAAAAAA);
                int sx = COL;
                int sy = barY + 36;
                for (int slot = 0; slot < 9; slot++) {
                    ItemStack stack = PlaybackEngine.file().itemAt(e.playerInventory[slot]);
                    g.renderItem(stack, sx, sy);
                    g.renderItemDecorations(this.font, stack, sx, sy);
                    sx += 18;
                }
                int armor = e.playerInventory[36];
                int off = e.playerInventory[40];
                g.drawString(this.font, "Armor: " + (armor >= 0 ? itemLabel(armor) : "—")
                        + "  ·  Offhand: " + (off >= 0 ? itemLabel(off) : "—"),
                        COL + 170, sy + 2, 0xFF888888);
            }
        }
    }

    private String itemLabel(int itemIndex) {
        ItemStack stack = PlaybackEngine.file().itemAt(itemIndex);
        return stack.isEmpty() ? "—" : stack.getHoverName().getString();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        double mouseX = event.x();
        double mouseY = event.y();
        int button = event.button();
        // Scrubber interaction on the timeline strip.
        int barY = this.height - 70;
        int barW = this.width - COL * 2;
        if (mouseY >= barY - 4 && mouseY <= barY + 9
                && mouseX >= COL && mouseX <= COL + barW
                && PlaybackEngine.isActive()) {
            int total = PlaybackEngine.durationTicks();
            if (total > 0) {
                int tick = (int) ((mouseX - COL) / (double) barW * total);
                PlaybackEngine.seek(tick);
                return true;
            }
        }
        // File list hit-test.
        int y = 30;
        for (int i = 0; i < files.size() && y + 10 < this.height - 150; i++) {
            if (mouseX >= COL + 40 && mouseX <= COL + 340 && mouseY >= y + 10 && mouseY <= y + 20) {
                selected = i;
                return true;
            }
            y += 10;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double xAmount, double yAmount) {
        ReplayChatOverlay.scroll((int) Math.signum(yAmount) * -3);
        return super.mouseScrolled(mouseX, mouseY, xAmount, yAmount);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_B) {
            this.minecraft.setScreen(null);
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
