package com.radiusreplay.playback;

/** Lightweight per-viewer replay display settings. */
public final class ReplaySettings {
    private static boolean replayHud = true;
    private static boolean chat = true;
    private static boolean inventory = true;
    private static boolean gui = true;
    private static boolean cursor = true;
    private static boolean crosshair = true;

    private ReplaySettings() {}
    public static boolean replayHud() { return replayHud; }
    public static boolean chat() { return chat; }
    public static boolean inventory() { return inventory; }
    public static boolean gui() { return gui; }
    public static boolean cursor() { return cursor; }
    public static boolean crosshair() { return crosshair; }
    public static void toggleHud() { replayHud = !replayHud; }
    public static void toggleChat() { chat = !chat; ReplayChatOverlay.setVisible(chat); }
    public static void toggleInventory() { inventory = !inventory; }
    public static void toggleGui() { gui = !gui; }
    public static void toggleCursor() { cursor = !cursor; }
    public static void toggleCrosshair() { crosshair = !crosshair; }
}
