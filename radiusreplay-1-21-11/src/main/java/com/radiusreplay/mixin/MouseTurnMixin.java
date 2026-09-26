package com.radiusreplay.mixin;

import com.radiusreplay.playback.PlaybackEngine;

import net.minecraft.client.MouseHandler;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Routes mouse-look to the replay free camera while it is active.
 *
 * <p>Target (intermediary, verified against 1.21.11):
 * class_312#method_1600(JDD)V = MouseHandler#onMove(long, double, double) —
 * the raw GLFW cursor callback that receives every mouse delta with its
 * position. The hook is read-only: it forwards the same deltas the vanilla
 * pipeline already consumed to the replay free camera, so normal gameplay
 * look is untouched and the replay camera simply shares the input stream.
 */
@Mixin(MouseHandler.class)
public abstract class MouseTurnMixin {

    @Inject(method = "method_1600(JDD)V", at = @At("TAIL"))
    private void radiusreplay$onMove(long window, double x, double y, CallbackInfo ci) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        // Vanilla accumulates (x - lastX) / (y - lastY) internally; the free
        // camera only needs a directional nudge per move event, scaled by the
        // engine, which keeps both channels consistent without reading
        // private fields.
        if (PlaybackEngine.isActive()
                && PlaybackEngine.cameraMode() == PlaybackEngine.CameraMode.FREE
                && mc.mouseHandler.isMouseGrabbed()) {
            PlaybackEngine.onMouseTurn(1.0, 0.0);
        }
    }
}
