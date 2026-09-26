package com.radiusreplay.mixin;

import com.radiusreplay.record.Recorder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records every block change the client sees during a replay session, so
 * playback can rebuild the world around the recorded player.
 *
 * <p>Target (intermediary, verified against 1.21.11):
 * class_1937#method_30092 = Level#setBlock(BlockPos, BlockState, int, int) —
 * the concrete 4-arg implementation behind every client block update
 * (single-block packets, section batches and block entities all end up
 * here). Capturing at TAIL keeps the hook read-only: vanilla's return value
 * is untouched and the recorder only observes the change.
 *
 * <p>The hook is one map insert into a bounded queue when a session is live,
 * and one null-check when it is not — zero cost during normal play.
 */
@Mixin(Level.class)
public abstract class BlockCaptureMixin {

    @Inject(method = "method_30092(Lnet/minecraft/class_2338;Lnet/minecraft/class_2680;II)Z", at = @At("TAIL"))
    private void radiusreplay$onSetBlock(BlockPos pos, BlockState state, int flags, int maxUpdateDepth,
            CallbackInfoReturnable<Boolean> cir) {
        if (pos == null || state == null) {
            return;
        }
        Recorder.onBlockChange(pos.asLong(), net.minecraft.world.level.block.Block.getId(state));
    }
}
