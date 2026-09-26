package com.radiusreplay.playback;

import com.radiusreplay.mixin.FreeCamAccessor;
import net.minecraft.client.Camera;

/**
 * Free-camera bridge: {@link Camera#setPosition(double, double, double)} and
 * {@link Camera#setRotation(float, float)} are protected, so this mixin
 * accessor exposes them for the detached replay camera. The intermediary
 * method ids are written directly (verified against the 1.21.11 mappings),
 * so no refmap is needed and the mixin applies identically in dev and
 * production launchers.
 *
 * <p>Camera ids on 1.21.11 (class_4184):
 * method_19327 = setPosition(double,double,double),
 * method_19325 = setRotation(float,float).
 */
public final class FreeCamAccess {
    private FreeCamAccess() {}

    public static void set(Camera cam, double x, double y, double z, float yaw, float pitch) {
        FreeCamAccessor invoker = (FreeCamAccessor) cam;
        invoker.radiusreplay$setPosition(x, y, z);
        invoker.radiusreplay$setRotation(yaw, pitch);
    }
}
