package com.radiusreplay.mixin;

import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Accessor mixin over Camera (class_4184) exposing the protected position
 * and rotation setters used by the replay free camera. Intermediary ids are
 * verified against 1.21.11:
 *
 * <ul>
 *   <li>method_19327(DDD)V = setPosition(double,double,double)</li>
 *   <li>method_19325(FF)V = setRotation(float,float)</li>
 * </ul>
 */
@Mixin(Camera.class)
public interface FreeCamAccessor {

    @Invoker("method_19327")
    void radiusreplay$setPosition(double x, double y, double z);

    @Invoker("method_19325")
    void radiusreplay$setRotation(float yaw, float pitch);
}
