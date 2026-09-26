package com.radiusreplay;

import com.mojang.blaze3d.platform.InputConstants;

import com.radiusreplay.playback.PlaybackEngine;
import com.radiusreplay.playback.ReplayChatOverlay;
import com.radiusreplay.playback.ReplayHud;
import com.radiusreplay.playback.ReplayScreen;
import com.radiusreplay.record.Recorder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RadiusReplay — original client-side replay recording & playback for
 * Minecraft 1.21.11 (Fabric, Java 21).
 *
 * <p>Everything is client-side: recordings capture the local player, nearby
 * entities, block changes, the player's inventory and chat as they reach
 * this client; playback rebuilds a ghost scene on top of the live world
 * without sending a single packet. Author: RadiusXD.
 */
public final class RadiusReplayClient implements ClientModInitializer {
    public static final String MOD_ID = "radiusreplay";
    public static final Logger LOGGER = LoggerFactory.getLogger("RadiusReplay");

    private static KeyMapping recordKey;
    private static KeyMapping menuKey;
    private static KeyMapping playPauseKey;
    private static KeyMapping chatKey;
    private static KeyMapping cameraKey;

    @Override
    public void onInitializeClient() {
        registerKeybinds();
        Recorder.register();
        PlaybackEngine.register();
        ReplayChatOverlay.register();
        ReplayHud.register();
        registerCommand();
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (Recorder.isRecording()) {
                Recorder.stopAndSave();
            }
            if (PlaybackEngine.isActive()) {
                PlaybackEngine.stop();
            }
            ReplayChatOverlay.clear();
        });
        LOGGER.info("RadiusReplay loaded — record with {}, replay via the menu. Client-side only.",
                "key");
    }

    private static void registerKeybinds() {
        KeyMapping.Category category = KeyMapping.Category.register(
                Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        recordKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.radiusreplay.record", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, category));
        menuKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.radiusreplay.menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, category));
        playPauseKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.radiusreplay.playpause", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        chatKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.radiusreplay.chat", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));
        cameraKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.radiusreplay.camera", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (recordKey.consumeClick()) {
                if (Recorder.isRecording()) {
                    Recorder.stopAndSave();
                } else if (PlaybackEngine.isActive()) {
                    feedback(client, "Stop playback before recording.");
                } else if (Recorder.start(client)) {
                    feedback(client, "Recording started.");
                } else {
                    feedback(client, "Cannot record right now.");
                }
            }
            while (menuKey.consumeClick()) {
                client.setScreen(new ReplayScreen());
            }
            while (playPauseKey.consumeClick()) {
                if (PlaybackEngine.isActive()) {
                    PlaybackEngine.toggle();
                }
            }
            while (chatKey.consumeClick()) {
                ReplayChatOverlay.toggleVisible();
            }
            while (cameraKey.consumeClick()) {
                if (PlaybackEngine.isActive()) {
                    PlaybackEngine.cycleCamera(client);
                }
            }
        });
    }

    private static void registerCommand() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("radiusreplay")
                        .then(ClientCommandManager.literal("record").executes(ctx -> {
                            Minecraft client = ctx.getSource().getClient();
                            if (Recorder.isRecording()) {
                                Recorder.stopAndSave();
                                ctx.getSource().sendFeedback(Component.literal("[RadiusReplay] Saved."));
                            } else if (Recorder.start(client)) {
                                ctx.getSource().sendFeedback(Component.literal("[RadiusReplay] Recording started."));
                            } else {
                                ctx.getSource().sendError(Component.literal("[RadiusReplay] Cannot record now."));
                            }
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("menu").executes(ctx -> {
                            ctx.getSource().getClient().execute(() ->
                                    ctx.getSource().getClient().setScreen(new ReplayScreen()));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("stop").executes(ctx -> {
                            if (PlaybackEngine.isActive()) {
                                PlaybackEngine.stop();
                                ctx.getSource().sendFeedback(Component.literal("[RadiusReplay] Playback stopped."));
                            } else {
                                ctx.getSource().sendError(Component.literal("[RadiusReplay] Nothing playing."));
                            }
                            return 1;
                        }))));
    }

    private static void feedback(Minecraft client, String message) {
        if (client.player != null) {
            client.player.displayClientMessage(Component.literal("[RadiusReplay] " + message), false);
        }
    }
}
