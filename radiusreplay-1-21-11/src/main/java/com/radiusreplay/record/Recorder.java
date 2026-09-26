package com.radiusreplay.record;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import com.radiusreplay.RadiusReplayClient;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * RadiusReplay's recording engine.
 *
 * <p>One END_CLIENT_TICK pass with strict change-only writes:
 *
 * <ul>
 *   <li>player position/rotation are written when they change at all;</li>
 *   <li>entity movement is a float offset from the entity's previous spot;</li>
 *   <li>equipment, inventory and flags are written only when they change;</li>
 *   <li>block changes are captured through the client's own block-update
 *       pipeline (a chunk-listener style hook on setBlock), so idle chunks
 *       cost nothing;</li>
 *   <li>chat arrives from the packet mixin (see ChatCapture).</li>
 * </ul>
 *
 * <p>Memory stays bounded on long sessions because idle players produce
 * nearly empty tick entries; a 10-minute session is typically a few hundred
 * kilobytes before compression.
 *
 * <p>Saving runs on a daemon thread so the game never freezes when stopping
 * a recording.
 */
public final class Recorder {
    public static final int PLAYER_PROXY_ID = -1;

    /** Live recording state, or null when not recording. */
    private static Session session;

    private Recorder() {}

    public static boolean isRecording() {
        return session != null;
    }

    public static int elapsedTicks() {
        return session != null ? session.tick : 0;
    }

    /** Starts a new recording session; returns false when one is running. */
    public static boolean start(Minecraft client) {
        if (session != null || client.player == null || client.level == null) {
            return false;
        }
        Session s = new Session();
        s.file = new ReplayFile();
        s.file.playerName = client.player.getGameProfile().name();
        s.file.playerUuid = String.valueOf(client.player.getGameProfile().id());
        s.file.slimModel = client.player.getSkin().model() == net.minecraft.world.entity.player.PlayerModelType.SLIM;
        s.file.startEpochMs = System.currentTimeMillis();
        s.equipmentCache = new int[6];
        s.inventoryCache = new int[41];
        java.util.Arrays.fill(s.equipmentCache, -1);
        java.util.Arrays.fill(s.inventoryCache, -1);
        session = s;
        return true;
    }

    /** Stops the current recording and saves it on a background thread. */
    public static void stopAndSave() {
        Session s = session;
        if (s == null) {
            return;
        }
        session = null;
        ReplayFile file = s.file;
        Thread t = new Thread(() -> {
            try {
                String base = "replay-" + java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                        .withZone(java.time.ZoneId.systemDefault())
                        .format(Instant.ofEpochMilli(file.startEpochMs));
                Path dir = modReplayDir();
                Files.createDirectories(dir);
                Path target = dir.resolve(base + ".radiusreplay");
                Path tmp = dir.resolve(base + ".radiusreplay.tmp");
                file.write(tmp);
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Minecraft.getInstance().execute(() -> info("Replay saved: " + target.getFileName()));
            } catch (Exception e) {
                RadiusReplayClient.LOGGER.error("Saving replay failed", e);
                Minecraft.getInstance().execute(() -> info("Replay save failed: " + e.getMessage()));
            }
        }, "RadiusReplay-Save");
        t.setDaemon(true);
        t.start();
    }

    /** Hard-aborts without saving (disconnects, world changes). */
    public static void abort() {
        session = null;
    }

    /** Chat + system message hook called from the packet mixin. */
    public static void onChat(boolean isPlayer, String sender, String text) {
        Session s = session;
        if (s == null || s.current == null) {
            return; // messages are attached to the current tick
        }
        s.current.chats.add(new ReplayFile.Chat(isPlayer, sender, text));
    }

    /** Per-tick capture pass. Registered once at startup. */
    public static void register() {
        ClientTickEvents.END_CLIENT_TICK.register(Recorder::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (session != null) {
                stopAndSave(); // keep the footage if the server drops
            }
        });
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> abort());
    }

    private static void tick(Minecraft client) {
        Session s = session;
        if (s == null) {
            return;
        }
        if (client.player == null || client.level == null) {
            stopAndSave();
            return;
        }

        ReplayFile.TickEntry e = new ReplayFile.TickEntry();
        e.tick = s.tick;
        s.current = e;

        // --- Player snapshot (absolute, cheap) ---
        LocalPlayer p = client.player;
        e.px = p.getX();
        e.py = p.getY();
        e.pz = p.getZ();
        e.yaw = p.getYRot();
        e.pitch = p.getXRot();
        e.moved = s.px != null && e.px != s.px | e.py != s.py | e.pz != s.pz;
        e.rotated = s.yaw != null && (e.yaw != s.yaw | e.pitch != s.pitch);
        e.selectedSlot = p.getInventory().getSelectedSlot();

        // --- Player health/food/air (only when changed) ---
        float health = p.getHealth();
        if (health != s.lastHealth) {
            e.health = health;
            s.lastHealth = health;
        }
        int food = p.getFoodData().getFoodLevel();
        if (food != s.lastFood) {
            e.food = food;
            s.lastFood = food;
        }
        int air = p.getAirSupply();
        if (air != s.lastAir) {
            e.air = air;
            s.lastAir = air;
        }

        // --- Player inventory (41 slots; only when changed) ---
        captureInventory(s, p.getInventory());

        // --- Player equipment + flags onto the proxy entity ---
        captureEquipment(s, e, PLAYER_PROXY_ID, p);

        // --- Entities ---
        captureEntities(s, client);

        // --- Block changes captured during the previous tick ---
        if (!s.blockQueue.isEmpty()) {
            e.blockChanges.addAll(s.blockQueue);
            s.blockQueue.clear();
        }

        s.file.tickEntries.add(e);
        s.file.ticks = s.tick + 1;
        s.tick++;

        s.px = e.px;
        s.py = e.py;
        s.pz = e.pz;
        s.yaw = e.yaw;
        s.pitch = e.pitch;
    }

    private static void captureInventory(Session s, Inventory inv) {
        boolean changed = false;
        for (int slot = 0; slot < 41; slot++) {
            int idx = inv.getItem(slot).isEmpty() ? -1 : s.file.internItem(inv.getItem(slot));
            if (idx != s.inventoryCache[slot]) {
                s.inventoryCache[slot] = idx;
                changed = true;
            }
        }
        if (changed) {
            s.pendingInventory = s.inventoryCache.clone();
        }
        if (s.pendingInventory != null && s.current != null) {
            s.current.playerInventory = s.pendingInventory;
            s.pendingInventory = null;
        }
    }

    private static void captureEquipment(Session s, ReplayFile.TickEntry e, int entityId, LivingEntity entity) {
        int[] now = new int[6];
        EquipmentSlot[] slots = {
                EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD
        };
        for (int i = 0; i < 6; i++) {
            ItemStack stack = entity.getItemBySlot(slots[i]);
            now[i] = stack.isEmpty() ? -1 : s.file.internItem(stack);
        }
        boolean eqChanged = false;
        for (int i = 0; i < 6; i++) {
            if (now[i] != s.equipmentCache[i]) {
                s.equipmentCache[i] = now[i];
                eqChanged = true;
            }
        }
        boolean sprinting = entity.isSprinting();
        boolean crouching = entity.isCrouching();
        boolean glowing = entity.isCurrentlyGlowing();
        boolean flagsChanged = sprinting != s.sprinting || crouching != s.crouching || glowing != s.glowing;

        if (eqChanged || flagsChanged || s.equipmentDirty) {
            ReplayFile.Delta d = new ReplayFile.Delta();
            d.entityId = entityId;
            if (eqChanged || s.equipmentDirty) {
                d.equipment = now.clone();
            }
            if (flagsChanged) {
                d.flagsChanged = true;
                d.sprinting = sprinting;
                d.crouching = crouching;
                d.glowing = glowing;
                s.sprinting = sprinting;
                s.crouching = crouching;
                s.glowing = glowing;
            }
            e.deltas.add(d);
            s.equipmentDirty = false;
        }
    }

    private static void captureEntities(Session s, Minecraft client) {
        // Track positions/rotations for every non-player entity in range.
        Set<Integer> alive = new HashSet<>();
        for (Entity entity : client.level.entitiesForRendering()) {
            if (entity == client.player) {
                continue;
            }
            int id = entity.getId();
            alive.add(id);

            ReplayFile.Delta d = null;
            EntityTrack track = s.tracks.get(id);
            if (track == null) {
                // First sighting: full snapshot.
                ReplayFile.FirstSnapshot f = new ReplayFile.FirstSnapshot();
                f.entityId = id;
                f.type = EntityType.getKey(entity.getType()).toString();
                f.x = entity.getX();
                f.y = entity.getY();
                f.z = entity.getZ();
                f.yaw = entity.getYRot();
                f.pitch = entity.getXRot();
                s.file.firsts.add(f);
                s.tracks.put(id, new EntityTrack(entity.getX(), entity.getY(), entity.getZ(),
                        entity.getYRot(), entity.getXRot()));
                d = new ReplayFile.Delta();
                d.entityId = id;
                d.moved = true;
                d.dx = 0;
                d.dy = 0;
                d.dz = 0;
                d.rotated = true;
                d.yaw = entity.getYRot();
                d.pitch = entity.getXRot();
                if (entity instanceof LivingEntity living) {
                    d.flagsChanged = true;
                    d.sprinting = living.isSprinting();
                    d.crouching = living.isCrouching();
                    d.glowing = entity.isCurrentlyGlowing();
                    track.sprinting = d.sprinting;
                    track.crouching = d.crouching;
                    track.glowing = d.glowing;
                }
                s.current.deltas.add(d);
                continue;
            }

            double dx = entity.getX() - track.x;
            double dy = entity.getY() - track.y;
            double dz = entity.getZ() - track.z;
            float dYaw = entity.getYRot() - track.yaw;
            float dPitch = entity.getXRot() - track.pitch;
            boolean moved = dx != 0 || dy != 0 || dz != 0;
            boolean rotated = dYaw != 0 || dPitch != 0;
            boolean flagsChanged = false;
            boolean sprinting = false;
            boolean crouching = false;
            boolean glowing = false;
            if (entity instanceof LivingEntity living) {
                sprinting = living.isSprinting() != track.sprinting;
                crouching = living.isCrouching() != track.crouching;
                glowing = entity.isCurrentlyGlowing() != track.glowing;
                flagsChanged = sprinting | crouching | glowing;
            }
            if (moved || rotated || flagsChanged) {
                d = new ReplayFile.Delta();
                d.entityId = id;
                d.moved = moved;
                if (moved) {
                    d.dx = (float) dx;
                    d.dy = (float) dy;
                    d.dz = (float) dz;
                    track.x = entity.getX();
                    track.y = entity.getY();
                    track.z = entity.getZ();
                }
                d.rotated = rotated;
                if (rotated) {
                    d.yaw = entity.getYRot();
                    d.pitch = entity.getXRot();
                    track.yaw = d.yaw;
                    track.pitch = d.pitch;
                }
                if (flagsChanged) {
                    d.flagsChanged = true;
                    d.sprinting = living2(entity).isSprinting();
                    d.crouching = living2(entity).isCrouching();
                    d.glowing = entity.isCurrentlyGlowing();
                    track.sprinting = d.sprinting;
                    track.crouching = d.crouching;
                    track.glowing = d.glowing;
                }
                s.current.deltas.add(d);
            }
        }

        // Entities that vanished: one despawn delta, then drop the track.
        Iterator<Map.Entry<Integer, EntityTrack>> it = s.tracks.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, EntityTrack> entry = it.next();
            if (!alive.contains(entry.getKey())) {
                ReplayFile.Delta d = new ReplayFile.Delta();
                d.entityId = entry.getKey();
                d.moved = false;
                d.rotated = false;
                d.flagsChanged = false;
                d.despawn = true;
                s.current.deltas.add(d);
                it.remove();
            }
        }
    }

    private static LivingEntity living2(Entity e) {
        return (LivingEntity) e;
    }

    /** Capture-side record of one entity's last known state. */
    private static final class EntityTrack {
        double x, y, z;
        float yaw, pitch;
        boolean sprinting, crouching, glowing;

        EntityTrack(double x, double y, double z, float yaw, float pitch) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    /** Active recording session state. */
    private static final class Session {
        ReplayFile file;
        int tick;
        ReplayFile.TickEntry current;

        Double px, py, pz;
        Float yaw, pitch;
        float lastHealth = -1;
        int lastFood = -1;
        int lastAir = -1;

        int[] equipmentCache;
        int[] inventoryCache;
        int[] pendingInventory;
        boolean equipmentDirty = true;
        boolean sprinting, crouching, glowing;

        /** Block changes queued by the block-listener hook this tick. */
        final java.util.ArrayDeque<ReplayFile.BlockChange> blockQueue = new java.util.ArrayDeque<>();

        final Map<Integer, EntityTrack> tracks = new HashMap<>();
    }

    /** Queue one block change (called from the world hook on the main thread). */
    public static void onBlockChange(long packedPos, int stateId) {
        Session s = session;
        if (s != null) {
            s.blockQueue.addLast(new ReplayFile.BlockChange(packedPos, stateId));
        }
    }

    public static Path modReplayDir() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getGameDir().resolve("radiusreplay");
    }

    private static void info(String msg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[RadiusReplay] " + msg), false);
        }
    }
}
