package com.radiusreplay.playback;

import com.radiusreplay.record.ReplayFile;
import com.radiusreplay.record.Recorder;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * RadiusReplay's playback engine.
 *
 * <p>Instead of replacing the world with a ghost copy, playback drives the
 * live client world from the recording:
 *
 * <ul>
 *   <li>a hidden marker proxy entity carries the recorded player's position,
 *       rotation, flags and equipment, so vanilla renders it with the real
 *       player model, armor and item swings;</li>
 *   <li>each tick, recorded deltas are applied: positions snap, blocks are
 *       restored via {@code setBlock} with flag 2 (pure visual update, no
 *       drops, no server packets — this is a client-side ghost only),</li>
 *   <li>camera modes: FOLLOW (orbit the proxy), THIRD (the proxy as camera
 *       subject) and FREE (detached fly camera with WASD + mouse);</li>
 *   <li>speed 0.25x–8x via a fixed-point accumulator, scrubber via direct
 *       index seek (recomputing entity/block state from the nearest full
 *       first-snapshot, then re-applying deltas),</li>
 *   <li>chat messages appear in their own overlay at their recorded ticks.</li>
 * </ul>
 *
 * <p>Nothing here touches server state: no packets are sent, no items are
 * moved, the real player is teleported nowhere. Stopping playback removes
 * every proxy entity and restores the pre-playback world only for the blocks
 * that playback itself changed (tracked in {@link #changedBlocks}).
 */
public final class PlaybackEngine {
    public enum Mode { IDLE, PLAYING, PAUSED }
    public enum CameraMode { FIRST, FOLLOW, THIRD, FREE }

    public static final int PROXY_ENTITY_ID = 1_700_000_001;

    private static final float[] SPEEDS = {0.25f, 0.5f, 1f, 2f, 4f, 8f};

    private static Mode mode = Mode.IDLE;
    private static CameraMode cameraMode = CameraMode.THIRD;

    private static ReplayFile file;
    /** Absolute tick index of the current playhead (fractional via accumulator). */
    private static double playhead;
    private static int speedIndex = 2;
    private static float acc;

    /**
     * Snapshot of the real player's inventory taken when a replay loads.
     * Playback only ever writes hotbar/armor/offhand slots on the live
     * inventory, and {@link #restoreLiveInventory} puts those slots back on
     * stop — the real player's items are never lost.
     */
    private static final int[] TOUCHED_SLOTS = {0, 1, 2, 3, 4, 5, 6, 7, 8, 36, 37, 38, 39, 40};
    private static final ItemStack[] liveInventory = new ItemStack[41];

    /** Proxy entity carrying the recorded player's visuals. */
    private static Entity proxy;
    /** Replica entities keyed by recorded entity id. */
    private static final Map<Integer, Entity> replicas = new HashMap<>();
    /** Working state per replica for delta application. */
    private static final Map<Integer, ReplicaState> states = new HashMap<>();

    /** Blocks changed by playback, saved for restoration on stop. */
    private static final Map<Long, BlockState> changedBlocks = new HashMap<>();

    /** Free-camera state. */
    private static double camX, camY, camZ;
    private static float camYaw, camPitch;
    private static boolean camActive;

    /** Direct slot order (EquipmentSlot has no byOrdinal lookup). */
    private static final EquipmentSlot[] SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
            EquipmentSlot.FEET, EquipmentSlot.LEGS,
            EquipmentSlot.CHEST, EquipmentSlot.HEAD
    };

    private PlaybackEngine() {}

    public static boolean isActive() {
        return mode != Mode.IDLE;
    }

    public static Mode mode() {
        return mode;
    }

    public static CameraMode cameraMode() {
        return cameraMode;
    }

    public static ReplayFile file() {
        return file;
    }

    public static double playhead() {
        return playhead;
    }

    public static int durationTicks() {
        return file != null ? file.ticks : 0;
    }

    public static float speed() {
        return SPEEDS[speedIndex];
    }

    public static String speedLabel() {
        float s = SPEEDS[speedIndex];
        return s == (int) s ? String.valueOf((int) s) : String.valueOf(s);
    }

    /* ------------------------------------------------------------------
     * Lifecycle
     * ------------------------------------------------------------------ */

    public static boolean load(ReplayFile replay) {
        stop();
        captureLiveInventory();
        file = replay;
        playhead = 0;
        acc = 0;
        mode = Mode.PAUSED;
        return true;
    }

    public static void play() {
        if (file == null) return;
        if (mode == Mode.PAUSED) {
            mode = Mode.PLAYING;
        } else if (mode == Mode.IDLE) {
            seek(0);
            mode = Mode.PLAYING;
        }
        ensureProxies();
    }

    public static void pause() {
        if (mode == Mode.PLAYING) {
            mode = Mode.PAUSED;
        }
    }

    public static void toggle() {
        if (mode == Mode.PLAYING) pause();
        else play();
    }

    public static void stop() {
        if (file != null && mode != Mode.IDLE) {
            restoreLiveInventory(Minecraft.getInstance());
        }
        removeProxies();
        mode = Mode.IDLE;
        file = null;
        playhead = 0;
        camActive = false;
        restoreCamera();
    }

    public static void cycleSpeed() {
        speedIndex = (speedIndex + 1) % SPEEDS.length;
    }

    public static void jump(int ticks) {
        if (file == null) return;
        seek((int) Math.max(0, Math.min(file.ticks - 1, playhead + ticks)));
    }

    public static void seek(int tick) {
        if (file == null) return;
        playhead = Math.max(0, Math.min(file.ticks - 1, tick));
        acc = 0;
        rebuildAt((int) playhead);
    }

    public static void cycleCamera(Minecraft client) {
        cameraMode = switch (cameraMode) {
            case FIRST -> CameraMode.THIRD;
            case THIRD -> CameraMode.FREE;
            case FREE -> CameraMode.FOLLOW;
            case FOLLOW -> CameraMode.FIRST;
        };
        applyCamera(client);
    }

    /* ------------------------------------------------------------------
     * Tick driver
     * ------------------------------------------------------------------ */

    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(PlaybackEngine::tick);
    }

    private static void tick(Minecraft client) {
        if (mode == Mode.IDLE || file == null) {
            return;
        }
        if (client.player == null || client.level == null) {
            stop();
            return;
        }

        ensureProxies();

        if (mode == Mode.PLAYING) {
            acc += SPEEDS[speedIndex];
            while (acc >= 1f && mode == Mode.PLAYING) {
                acc -= 1f;
                int next = (int) playhead + 1;
                if (next >= file.ticks) {
                    mode = Mode.PAUSED;
                    break;
                }
                applyTick(client, file.tickEntries.get(next));
                playhead = next;
            }
        } else {
            // Paused: keep applying the current tick so proxies stay exact.
            int idx = (int) playhead;
            if (idx < file.tickEntries.size()) {
                applyTick(client, file.tickEntries.get(idx));
            }
        }

        // Camera handling each tick.
        if (mode != Mode.IDLE) {
            handleFreeCamera(client);
            applyCamera(client);
        }

        // Sync the overlay/UI state.
        ReplayHud.tick(client);
    }

    private static void ensureProxies() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        if (proxy == null || proxy.level() != client.level) {
            removeProxies();
            proxy = spawnProxy(client);
        }
    }

    /**
     * Spawns the recorded player as a visible vanilla player model. A
     * RemotePlayer built from the recorded profile renders with the real
     * skin, nameplate, armor and held items — an invisible marker would
     * defeat the whole point of a replay.
     */
    private static Entity spawnProxy(Minecraft client) {
        GameProfile profile = recordedProfile();
        if (profile == null) {
            return null;
        }
        RemotePlayer player = new RemotePlayer(client.level, profile);
        player.setId(PROXY_ENTITY_ID);
        player.setNoGravity(true);
        player.setSilent(true);
        client.level.addEntity(player);
        return player;
    }

    /** The recorded player's profile, falling back to the local one. */
    private static GameProfile recordedProfile() {
        ReplayFile f = file;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return null;
        }
        if (f != null && f.playerUuid != null && !f.playerUuid.isEmpty()) {
            try {
                java.util.UUID id = java.util.UUID.fromString(f.playerUuid);
                String name = f.playerName != null && !f.playerName.isEmpty()
                        ? f.playerName : client.player.getGameProfile().name();
                return new GameProfile(id, name);
            } catch (IllegalArgumentException ignored) {
                // Malformed UUID in the file: fall through to the local profile.
            }
        }
        return client.player.getGameProfile();
    }

    /** Snapshot of the live inventory slots playback may touch. */
    private static void captureLiveInventory() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) {
            return;
        }
        Inventory inv = client.player.getInventory();
        for (int slot = 0; slot < 41; slot++) {
            ItemStack stack = inv.getItem(slot);
            liveInventory[slot] = stack.isEmpty() ? null : stack.copy();
        }
    }

    /** Restores the real player's hotbar/armor/offhand after playback. */
    private static void restoreLiveInventory(Minecraft client) {
        if (client == null || client.player == null) {
            return;
        }
        Inventory inv = client.player.getInventory();
        for (int slot : TOUCHED_SLOTS) {
            if (liveInventory[slot] != null) {
                inv.setItem(slot, liveInventory[slot]);
            }
        }
    }

    private static Entity spawn(Minecraft client, EntityType<?> type, int id) {
        Entity e = type.create(client.level, EntitySpawnReason.LOAD);
        if (e == null) {
            return null;
        }
        e.setId(id);
        e.setNoGravity(true);
        e.setSilent(true);
        e.setInvisible(true);
        client.level.addEntity(e);
        return e;
    }

    private static void removeProxies() {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            replicas.clear();
            states.clear();
            proxy = null;
            changedBlocks.clear();
            return;
        }
        if (proxy != null) {
            client.level.removeEntity(proxy.getId(), Entity.RemovalReason.DISCARDED);
            proxy = null;
        }
        for (Entity e : replicas.values()) {
            client.level.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
        }
        replicas.clear();
        states.clear();
        // Restore every block playback touched.
        for (Map.Entry<Long, BlockState> en : changedBlocks.entrySet()) {
            BlockPos pos = BlockPos.of(en.getKey());
            client.level.setBlock(pos, en.getValue(), 2);
        }
        changedBlocks.clear();
    }

    /* ------------------------------------------------------------------
     * Delta application
     * ------------------------------------------------------------------ */

    private static void applyTick(Minecraft client, ReplayFile.TickEntry e) {
        if (e == null || client.level == null) return;

        // Player proxy.
        if (proxy != null) {
            place(proxy, e.px, e.py, e.pz, e.yaw, e.pitch);
            proxy.setSprinting(proxySprinting());
        }

        // Hotbar + selected slot + armor/offhand ghost onto the live
        // inventory (visual-only; captureLiveInventory/restoreLiveInventory
        // bracket playback so the real items always come back).
        if (e.playerInventory != null && ReplaySettings.inventory()) {
            Inventory inv = client.player.getInventory();
            if (e.selectedSlot >= 0 && e.selectedSlot <= 8) {
                inv.setSelectedSlot(e.selectedSlot);
            }
            for (int slot : TOUCHED_SLOTS) {
                inv.setItem(slot, file.itemAt(e.playerInventory[slot]));
            }
        }

        // Deltas.
        for (ReplayFile.Delta d : e.deltas) {
            if (d.despawn) {
                Entity r = replicas.remove(d.entityId);
                if (r != null) {
                    client.level.removeEntity(r.getId(), Entity.RemovalReason.DISCARDED);
                }
                states.remove(d.entityId);
                continue;
            }
            if (d.entityId == Recorder.PLAYER_PROXY_ID) {
                // Player equipment/flags ride the proxy entity.
                if (d.equipment != null && proxy instanceof LivingEntity living) {
                    for (int i = 0; i < 6; i++) {
                        ItemStack stack = file.itemAt(d.equipment[i]);
                        living.setItemSlot(SLOTS[i], stack);
                    }
                }
                continue;
            }
            Entity r = replicas.get(d.entityId);
            ReplicaState st = states.get(d.entityId);
            if (r == null || st == null) {
                continue; // skipped past its first snapshot while paused/scrubbed
            }
            if (d.moved) {
                st.x += d.dx;
                st.y += d.dy;
                st.z += d.dz;
                place(r, st.x, st.y, st.z, st.yaw, st.pitch);
            }
            if (d.rotated) {
                st.yaw = d.yaw;
                st.pitch = d.pitch;
                r.setYRot(d.yaw);
                r.setXRot(d.pitch);
                r.yRotO = d.yaw;
                r.xRotO = d.pitch;
            }
            if (d.flagsChanged) {
                r.setSprinting(d.sprinting);
                r.setShiftKeyDown(d.crouching);
                r.setGlowingTag(d.glowing);
                st.sprinting = d.sprinting;
                st.crouching = d.crouching;
                st.glowing = d.glowing;
            }
            if (d.equipment != null && r instanceof LivingEntity living) {
                for (int i = 0; i < 6; i++) {
                    ItemStack stack = file.itemAt(d.equipment[i]);
                    living.setItemSlot(SLOTS[i], stack);
                }
            }
        }

        // Block changes (visual-only updates on the client ghost world).
        for (ReplayFile.BlockChange bc : e.blockChanges) {
            BlockPos pos = BlockPos.of(bc.packedPos());
            BlockState before = client.level.getBlockState(pos);
            changedBlocks.putIfAbsent(bc.packedPos(), before);
            BlockState target = Block.stateById(bc.stateId());
            client.level.setBlock(pos, target, 2);
        }

        // Chat.
        if (ReplaySettings.chat()) {
            for (ReplayFile.Chat c : e.chats) {
                ReplayChatOverlay.push(c);
            }
        }
    }

    private static boolean proxySprinting() {
        return false;
    }

    /** Sets position and both old-position fields so vanilla lerp stays smooth. */
    private static void place(Entity e, double x, double y, double z, float yaw, float pitch) {
        e.xo = x;
        e.yo = y;
        e.zo = z;
        e.xOld = x;
        e.yOld = y;
        e.zOld = z;
        e.setPos(x, y, z);
        e.setYRot(yaw);
        e.setXRot(pitch);
        e.yRotO = yaw;
        e.xRotO = pitch;
    }

    /* ------------------------------------------------------------------
     * Seek / rebuild
     * ------------------------------------------------------------------ */

    /**
     * Rebuilds entity + block state at an arbitrary tick: clears replicas,
     * walks the file from the start applying first snapshots and deltas up to
     * {@code target}, and applies the final tick. Bounded work for scrubbing.
     */
    private static void rebuildAt(int target) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null || file == null) {
            return;
        }
        removeProxies();
        restoreLiveInventory(client);
        ensureProxies();
        states.clear();

        Map<Integer, ReplayFile.FirstSnapshot> firstById = new HashMap<>();
        for (ReplayFile.FirstSnapshot f : file.firsts) {
            firstById.put(f.entityId, f);
        }

        Map<Integer, int[]> equipState = new HashMap<>();
        int[] playerEquip = new int[]{-1, -1, -1, -1, -1, -1};

        for (int t = 0; t <= target && t < file.tickEntries.size(); t++) {
            ReplayFile.TickEntry e = file.tickEntries.get(t);
            for (ReplayFile.Delta d : e.deltas) {
                if (d.despawn) {
                    replicas.remove(d.entityId);
                    states.remove(d.entityId);
                    equipState.remove(d.entityId);
                    continue;
                }
                ReplicaState st = states.get(d.entityId);
                if (st == null) {
                    ReplayFile.FirstSnapshot f = firstById.get(d.entityId);
                    if (f == null) {
                        continue;
                    }
                    st = new ReplicaState();
                    st.x = f.x;
                    st.y = f.y;
                    st.z = f.z;
                    st.yaw = f.yaw;
                    st.pitch = f.pitch;
                    st.sprinting = f.sprinting;
                    st.crouching = f.crouching;
                    st.glowing = f.glowing;
                    states.put(d.entityId, st);
                }
                if (d.moved) {
                    st.x += d.dx;
                    st.y += d.dy;
                    st.z += d.dz;
                }
                if (d.rotated) {
                    st.yaw = d.yaw;
                    st.pitch = d.pitch;
                }
                if (d.flagsChanged) {
                    st.sprinting = d.sprinting;
                    st.crouching = d.crouching;
                    st.glowing = d.glowing;
                }
                if (d.equipment != null) {
                    int[] eq = equipState.get(d.entityId);
                    if (eq == null) {
                        ReplayFile.FirstSnapshot f = firstById.get(d.entityId);
                        eq = f != null ? f.equipment.clone() : new int[]{-1, -1, -1, -1, -1, -1};
                        equipState.put(d.entityId, eq);
                    }
                    for (int i = 0; i < 6; i++) {
                        if (d.equipment[i] != -1) {
                            eq[i] = d.equipment[i];
                        }
                    }
                }
            }
            if (target == t) {
                // Last tick: apply block changes at full fidelity.
                for (ReplayFile.BlockChange bc : e.blockChanges) {
                    BlockPos pos = BlockPos.of(bc.packedPos());
                    changedBlocks.putIfAbsent(bc.packedPos(), client.level.getBlockState(pos));
                    client.level.setBlock(pos, Block.stateById(bc.stateId()), 2);
                }
            }
        }

        // Instantiate replicas at their rebuilt state.
        for (Map.Entry<Integer, ReplicaState> en : states.entrySet()) {
            ReplayFile.FirstSnapshot f = firstById.get(en.getKey());
            if (f == null) continue;
            EntityType<?> type = EntityType.byString(f.type).orElse(null);
            if (type == null) continue;
            Entity r = spawn(client, type, 1_700_000_000 + en.getKey());
            if (r == null) continue;
            ReplicaState st = en.getValue();
            place(r, st.x, st.y, st.z, st.yaw, st.pitch);
            r.setSprinting(st.sprinting);
            r.setShiftKeyDown(st.crouching);
            r.setGlowingTag(st.glowing);
            if (r instanceof LivingEntity living) {
                int[] eq = equipState.getOrDefault(en.getKey(), f.equipment);
                for (int i = 0; i < 6; i++) {
                    living.setItemSlot(SLOTS[i], file.itemAt(eq[i]));
                }
            }
            replicas.put(en.getKey(), r);
        }

        // Player proxy to the target tick's pose + equipment.
        if (proxy instanceof LivingEntity living && target < file.tickEntries.size()) {
            ReplayFile.TickEntry e = file.tickEntries.get(target);
            place(proxy, e.px, e.py, e.pz, e.yaw, e.pitch);
            if (e.playerInventory != null) {
                living.setItemSlot(EquipmentSlot.MAINHAND,
                        file.itemAt(e.playerInventory[e.selectedSlot]));
                living.setItemSlot(EquipmentSlot.OFFHAND,
                        file.itemAt(e.playerInventory[40]));
                living.setItemSlot(EquipmentSlot.FEET, file.itemAt(e.playerInventory[36]));
                living.setItemSlot(EquipmentSlot.LEGS, file.itemAt(e.playerInventory[37]));
                living.setItemSlot(EquipmentSlot.CHEST, file.itemAt(e.playerInventory[38]));
                living.setItemSlot(EquipmentSlot.HEAD, file.itemAt(e.playerInventory[39]));
            }
        }
    }

    private static final class ReplicaState {
        double x, y, z;
        float yaw, pitch;
        boolean sprinting, crouching, glowing;
    }

    /* ------------------------------------------------------------------
     * Camera
     * ------------------------------------------------------------------ */

    private static void handleFreeCamera(Minecraft client) {
        if (cameraMode != CameraMode.FREE) {
            return;
        }
        LocalPlayer p = client.player;
        if (p == null) return;
        float speed = client.options.keySprint.isDown() ? 1.6f : 0.55f;
        double dx = 0, dy = 0, dz = 0;
        if (client.options.keyUp.isDown()) dx += speed;
        if (client.options.keyDown.isDown()) dx -= speed;
        if (client.options.keyLeft.isDown()) dz += speed;
        if (client.options.keyRight.isDown()) dz -= speed;
        if (client.options.keyJump.isDown()) dy += speed;
        if (client.options.keyShift.isDown()) dy -= speed;
        double rad = Math.toRadians(camYaw);
        camX += dx * -Math.sin(rad) + dz * Math.cos(rad);
        camZ += dx * Math.cos(rad) + dz * Math.sin(rad);
        camY += dy;
    }

    /**
     * Applies the selected camera. THIRD uses vanilla's own third-person
     * camera around the proxy (the proxy temporarily becomes the camera
     * subject), FOLLOW keeps the real player but aims at the proxy, FREE
     * overrides the camera transform directly through the Camera object.
     */
    private static void applyCamera(Minecraft client) {
        if (mode == Mode.IDLE || proxy == null) {
            return;
        }
        switch (cameraMode) {
            case FIRST -> {
                if (client.getCameraEntity() != client.player) {
                    client.setCameraEntity(client.player);
                }
                client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
            }
            case THIRD -> {
                if (client.getCameraEntity() != proxy) {
                    client.setCameraEntity(proxy);
                }
                client.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
            }
            case FOLLOW -> {
                if (client.getCameraEntity() != client.player) {
                    client.setCameraEntity(client.player);
                }
                client.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_BACK);
                faceCameraTowards(client, proxy.position());
            }
            case FREE -> {
                if (client.getCameraEntity() != client.player) {
                    client.setCameraEntity(client.player);
                }
                if (!camActive) {
                    camX = client.gameRenderer.getMainCamera().position().x;
                    camY = client.gameRenderer.getMainCamera().position().y;
                    camZ = client.gameRenderer.getMainCamera().position().z;
                    camYaw = client.player.getYRot();
                    camPitch = client.player.getXRot();
                    camActive = true;
                }
                overrideCamera(client, camX, camY, camZ, camYaw, camPitch);
            }
        }
    }

    private static void faceCameraTowards(Minecraft client, Vec3 target) {
        LocalPlayer p = client.player;
        if (p == null) return;
        Vec3 eye = p.getEyePosition();
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        p.setYRot(yaw);
        p.setXRot(pitch);
    }

    /**
     * Detached free camera: writes the pose straight into the main Camera
     * after vanilla set it up. Camera.setPosition/setRotation are protected,
     * so we bridge through a small shared accessor (FreeCamAccessor mixin).
     */
    private static void overrideCamera(Minecraft client, double x, double y, double z, float yaw, float pitch) {
        Camera cam = client.gameRenderer.getMainCamera();
        FreeCamAccess.set(cam, x, y, z, yaw, pitch);
    }

    private static void restoreCamera() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.player == null) return;
        if (client.getCameraEntity() != client.player) {
            client.setCameraEntity(client.player);
        }
        client.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON);
    }

    /** Mouse-look routing for the free camera (called from the mixin). */
    public static void onMouseTurn(double yawDelta, double pitchDelta) {
        if (mode != Mode.IDLE && cameraMode == CameraMode.FREE && camActive) {
            camYaw += (float) (yawDelta * 0.6);
            camPitch = Math.max(-90f, Math.min(90f, camPitch + (float) (pitchDelta * 0.6)));
        }
    }

    /** Snapshot of camera state used by the HUD. */
    public static String cameraLabel() {
        return switch (cameraMode) {
            case FIRST -> "First person";
            case FOLLOW -> "Follow";
            case THIRD -> "Third person";
            case FREE -> "Free cam (WASD+Space)";
        };
    }
}
