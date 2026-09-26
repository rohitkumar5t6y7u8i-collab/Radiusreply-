package com.radiusreplay.mixin;

import com.radiusreplay.record.Recorder;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.game.ClientboundDisguisedChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Records chat into the active replay session. Targets are written directly
 * in intermediary names (verified against the 1.21.11 mappings), so the mixin
 * applies identically in dev and production without a refmap:
 *
 * <ul>
 *   <li>class_634#method_43595(class_7438) = handlePlayerChat —
 *       ClientboundPlayerChatPacket (signed player chat)</li>
 *   <li>class_634#method_43596(class_7439) = handleSystemChat —
 *       ClientboundSystemChatPacket (system messages, join/leave, deaths)</li>
 *   <li>class_634#method_45724(class_7827) = handleDisguisedChat —
 *       ClientboundDisguisedChatPacket (server-emoted or forged chat)</li>
 * </ul>
 *
 * Capturing at HEAD on the network thread is read-only: the mod never
 * cancels, rewrites or delays any packet, and replayed chat during playback
 * is drawn only by the mod's own overlay — the live chat pipeline is never
 * touched.
 */
@Mixin(ClientPacketListener.class)
public abstract class ChatCaptureMixin {

    @Inject(method = "method_43595(Lnet/minecraft/class_7438;)V", at = @At("HEAD"))
    private void radiusreplay$onPlayerChat(ClientboundPlayerChatPacket packet, CallbackInfo ci) {
        // Record the plain message body; the sender name is resolved from
        // the client's own player list (read-only, no packets touched).
        String name = playerName(packet.sender());
        Recorder.onChat(true, name, packet.body() != null
                ? packet.body().content()
                : packet.unsignedContent() != null ? packet.unsignedContent().getString() : "");
    }

    @Inject(method = "method_43596(Lnet/minecraft/class_7439;)V", at = @At("HEAD"))
    private void radiusreplay$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        Recorder.onChat(false, "", packet.content() != null ? packet.content().getString() : "");
    }

    @Inject(method = "method_45724(Lnet/minecraft/class_7827;)V", at = @At("HEAD"))
    private void radiusreplay$onDisguisedChat(ClientboundDisguisedChatPacket packet, CallbackInfo ci) {
        Recorder.onChat(true, "", packet.message() != null ? packet.message().getString() : "");
    }

    private static String playerName(java.util.UUID uuid) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.getConnection() != null && uuid != null) {
            net.minecraft.client.multiplayer.PlayerInfo info = mc.getConnection().getPlayerInfo(uuid);
            if (info != null && info.getProfile() != null) {
                return info.getProfile().name();
            }
        }
        return uuid != null ? uuid.toString() : "";
    }
}
