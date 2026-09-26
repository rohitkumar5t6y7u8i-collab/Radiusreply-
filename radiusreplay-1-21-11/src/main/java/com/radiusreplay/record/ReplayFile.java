package com.radiusreplay.record;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * RadiusReplay's own file format: a compact, delta-based, tick-indexed
 * container written by the recorder and read by the playback engine.
 *
 * <p>Design goals: small files on low-end devices, bounded memory, and
 * graceful failure on truncated or corrupted data (the playback engine wraps
 * reads in try/catch and rejects bad files with a chat message instead of
 * crashing).
 *
 * <p>Layout: magic "RPL1", format version, header fields, one shared item
 * table (each unique stack is stored once as compressed NBT), one full
 * snapshot per entity at its first appearance, then one entry per recorded
 * tick. Tick entries store only what changed since the previous tick (player
 * position/rotation are absolute but cheap; entity movement is a float
 * offset; equipment and flags only when they change).
 *
 * <p>Everything is wrapped in one GZIP stream, which compresses the highly
 * repetitive tick data extremely well.
 */
public final class ReplayFile {
    /** Format version; bumped on incompatible changes. */
    public static final int VERSION = 2;
    private static final int MAGIC = 0x52504C31; // "RPL1"

    public String playerName = "Player";
    public String playerUuid = "";
    public boolean slimModel;
    public long startEpochMs;
    public int ticks;

    /** Full first-appearance snapshots, one per entity seen in the replay. */
    public final List<FirstSnapshot> firsts = new ArrayList<>();
    /** One entry per recorded tick, in order. */
    public final List<TickEntry> tickEntries = new ArrayList<>();

    private final List<ItemStack> itemTable = new ArrayList<>();
    private final Map<String, Integer> itemTableIndex = new HashMap<>();

    /** A full state record for one entity, stored once at its first appearance. */
    public static final class FirstSnapshot {
        public int entityId;
        public String type;
        public double x, y, z;
        public float yaw, pitch;
        /** 6 slots: main hand, offhand, feet, legs, chest, head; -1 = empty. */
        public int[] equipment = new int[]{-1, -1, -1, -1, -1, -1};
        public boolean sprinting;
        public boolean crouching;
        public boolean glowing;
    }

    /** One tick's worth of changes. Lists may be empty (nothing happened). */
    public static final class TickEntry {
        public int tick;
        public double px, py, pz;
        public float yaw, pitch;
        public boolean moved, rotated;
        public int selectedSlot;
        public float health = -1;
        public int food = -1;
        public int air = -1;
        /** Non-null only when the player's 41 inventory slots changed:
         *  0-8 hotbar, 9-35 main, 36-39 armor (feet,legs,chest,head), 40 offhand.
         *  Values are item-table indices; -1 = empty slot. */
        public int[] playerInventory;
        public final List<Delta> deltas = new ArrayList<>();
        public final List<Chat> chats = new ArrayList<>();
        public final List<BlockChange> blockChanges = new ArrayList<>();
    }

    /** A block state change recorded at this tick (position + state id). */
    public record BlockChange(long packedPos, int stateId) {}

    /** A per-entity change inside one tick. */
    public static final class Delta {
        public int entityId;
        public boolean moved;
        public float dx, dy, dz;
        public boolean rotated;
        public float yaw, pitch;
        public boolean flagsChanged;
        public boolean sprinting, crouching, glowing;
        /** Non-null only when any equipment slot changed; -1 = unchanged. */
        public int[] equipment;
        /** True when the entity despawned (all other fields meaningless). */
        public boolean despawn;
    }

    /** A chat/system message recorded at this tick. */
    public record Chat(boolean isPlayer, String sender, String text) {}

    /* ------------------------------------------------------------------
     * Item table
     * ------------------------------------------------------------------ */

    /**
     * Registers a stack in the shared table (deduplicated) and returns its
     * index. Recording-side only; playback just reads the table.
     */
    public int internItem(ItemStack stack) {
        String key = stack.getItem() + "|" + stack.getCount() + "|" + stack.getDamageValue();
        Integer existing = itemTableIndex.get(key);
        if (existing != null) {
            return existing;
        }
        int idx = itemTable.size();
        itemTable.add(stack.copy());
        itemTableIndex.put(key, idx);
        return idx;
    }

    /** Playback-side stack lookup; empty stack for out-of-range indices. */
    public ItemStack itemAt(int index) {
        if (index < 0 || index >= itemTable.size()) {
            return ItemStack.EMPTY;
        }
        return itemTable.get(index);
    }

    public int itemCount() {
        return itemTable.size();
    }

    /* ------------------------------------------------------------------
     * Serialization
     * ------------------------------------------------------------------ */

    /**
     * Reads a replay file. Throws IOException on any structural problem; the
     * caller is expected to show a friendly message rather than crash.
     */
    public static ReplayFile read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(file), 8192), 16384))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Not a RadiusReplay file");
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("Unsupported replay version " + version);
            }
            ReplayFile rf = new ReplayFile();
            rf.playerName = in.readUTF();
            rf.playerUuid = in.readUTF();
            rf.slimModel = in.readBoolean();
            rf.startEpochMs = in.readLong();
            rf.ticks = in.readInt();

            // Item table: each stack as an NBT compound blob.
            net.minecraft.core.HolderLookup.Provider ops = opsProvider();
            int itemCount = in.readInt();
            for (int i = 0; i < itemCount; i++) {
                int n = in.readInt();
                byte[] bytes = new byte[n];
                in.readFully(bytes);
                CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)), NbtAccounter.unlimitedHeap());
                ItemStack stack = ItemStack.CODEC.parse(
                                ops.createSerializationContext(NbtOps.INSTANCE), tag)
                        .result().orElse(ItemStack.EMPTY);
                rf.itemTable.add(stack);
            }

            // Entity first snapshots.
            int firstCount = in.readInt();
            for (int i = 0; i < firstCount; i++) {
                FirstSnapshot f = new FirstSnapshot();
                f.entityId = in.readInt();
                f.type = in.readUTF();
                f.x = in.readDouble();
                f.y = in.readDouble();
                f.z = in.readDouble();
                f.yaw = in.readFloat();
                f.pitch = in.readFloat();
                for (int s = 0; s < 6; s++) {
                    f.equipment[s] = in.readInt();
                }
                f.sprinting = in.readBoolean();
                f.crouching = in.readBoolean();
                f.glowing = in.readBoolean();
                rf.firsts.add(f);
            }

            // Ticks.
            int tickCount = in.readInt();
            for (int i = 0; i < tickCount; i++) {
                TickEntry e = new TickEntry();
                e.tick = in.readInt();
                e.px = in.readDouble();
                e.py = in.readDouble();
                e.pz = in.readDouble();
                e.yaw = in.readFloat();
                e.pitch = in.readFloat();
                e.moved = in.readBoolean();
                e.rotated = in.readBoolean();
                e.selectedSlot = in.readByte();
                e.health = in.readFloat();
                e.food = in.readByte();
                e.air = in.readByte();

                if (in.readBoolean()) {
                    e.playerInventory = new int[41];
                    for (int s = 0; s < 41; s++) {
                        e.playerInventory[s] = in.readInt();
                    }
                }

                int blockCount = in.readInt();
                for (int b = 0; b < blockCount; b++) {
                    e.blockChanges.add(new BlockChange(in.readLong(), in.readInt()));
                }

                int deltaCount = in.readInt();
                for (int d = 0; d < deltaCount; d++) {
                    Delta dl = new Delta();
                    dl.entityId = in.readInt();
                    dl.moved = in.readBoolean();
                    if (dl.moved) {
                        dl.dx = in.readFloat();
                        dl.dy = in.readFloat();
                        dl.dz = in.readFloat();
                    }
                    dl.rotated = in.readBoolean();
                    if (dl.rotated) {
                        dl.yaw = in.readFloat();
                        dl.pitch = in.readFloat();
                    }
                    dl.flagsChanged = in.readBoolean();
                    if (dl.flagsChanged) {
                        dl.sprinting = in.readBoolean();
                        dl.crouching = in.readBoolean();
                        dl.glowing = in.readBoolean();
                    }
                    if (in.readBoolean()) {
                        dl.equipment = new int[6];
                        for (int s = 0; s < 6; s++) {
                            dl.equipment[s] = in.readInt();
                        }
                    }
                    dl.despawn = in.readBoolean();
                    e.deltas.add(dl);
                }

                int chatCount = in.readInt();
                for (int c = 0; c < chatCount; c++) {
                    boolean isPlayer = in.readBoolean();
                    String sender = in.readUTF();
                    String text = in.readUTF();
                    e.chats.add(new Chat(isPlayer, sender, text));
                }
                rf.tickEntries.add(e);
            }
            return rf;
        } catch (java.io.EOFException | java.util.zip.ZipException e) {
            throw new IOException("Replay file is truncated or corrupted", e);
        }
    }

    /** Registry source for item codecs: the live client level when present. */
    private static net.minecraft.core.HolderLookup.Provider opsProvider() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.level != null) {
            return mc.level.registryAccess();
        }
        return net.minecraft.core.RegistryAccess.EMPTY;
    }

    /**
     * Writes the replay. The caller invokes this on a save thread; the write
     * itself is a single pass over the data.
     */
    public void write(Path file) throws IOException {
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(Files.newOutputStream(file), 8192)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeUTF(playerName);
            out.writeUTF(playerUuid);
            out.writeBoolean(slimModel);
            out.writeLong(startEpochMs);
            out.writeInt(ticks);

            // Item table.
            com.mojang.serialization.DynamicOps<Tag> ops =
                    opsProvider().createSerializationContext(NbtOps.INSTANCE);
            out.writeInt(itemTable.size());
            for (ItemStack stack : itemTable) {
                CompoundTag tag = (CompoundTag) ItemStack.CODEC.encodeStart(ops, stack)
                        .getOrThrow(msg -> new IOException("Item encode failed: " + msg));
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                NbtIo.write(tag, new DataOutputStream(bos));
                byte[] bytes = bos.toByteArray();
                out.writeInt(bytes.length);
                out.write(bytes);
            }

            // First snapshots.
            out.writeInt(firsts.size());
            for (FirstSnapshot f : firsts) {
                out.writeInt(f.entityId);
                out.writeUTF(f.type);
                out.writeDouble(f.x);
                out.writeDouble(f.y);
                out.writeDouble(f.z);
                out.writeFloat(f.yaw);
                out.writeFloat(f.pitch);
                for (int s = 0; s < 6; s++) {
                    out.writeInt(f.equipment[s]);
                }
                out.writeBoolean(f.sprinting);
                out.writeBoolean(f.crouching);
                out.writeBoolean(f.glowing);
            }

            // Tick entries.
            out.writeInt(tickEntries.size());
            for (TickEntry e : tickEntries) {
                out.writeInt(e.tick);
                out.writeDouble(e.px);
                out.writeDouble(e.py);
                out.writeDouble(e.pz);
                out.writeFloat(e.yaw);
                out.writeFloat(e.pitch);
                out.writeBoolean(e.moved);
                out.writeBoolean(e.rotated);
                out.writeByte(e.selectedSlot);
                out.writeFloat(e.health);
                out.writeByte(e.food);
                out.writeByte(e.air);

                out.writeBoolean(e.playerInventory != null);
                if (e.playerInventory != null) {
                    for (int s = 0; s < 41; s++) {
                        out.writeInt(e.playerInventory[s]);
                    }
                }

                out.writeInt(e.blockChanges.size());
                for (BlockChange b : e.blockChanges) {
                    out.writeLong(b.packedPos());
                    out.writeInt(b.stateId());
                }

                out.writeInt(e.deltas.size());
                for (Delta d : e.deltas) {
                    out.writeInt(d.entityId);
                    out.writeBoolean(d.moved);
                    if (d.moved) {
                        out.writeFloat(d.dx);
                        out.writeFloat(d.dy);
                        out.writeFloat(d.dz);
                    }
                    out.writeBoolean(d.rotated);
                    if (d.rotated) {
                        out.writeFloat(d.yaw);
                        out.writeFloat(d.pitch);
                    }
                    out.writeBoolean(d.flagsChanged);
                    if (d.flagsChanged) {
                        out.writeBoolean(d.sprinting);
                        out.writeBoolean(d.crouching);
                        out.writeBoolean(d.glowing);
                    }
                    out.writeBoolean(d.equipment != null);
                    if (d.equipment != null) {
                        for (int s = 0; s < 6; s++) {
                            out.writeInt(d.equipment[s]);
                        }
                    }
                    out.writeBoolean(d.despawn);
                }

                out.writeInt(e.chats.size());
                for (Chat c : e.chats) {
                    out.writeBoolean(c.isPlayer());
                    out.writeUTF(c.sender());
                    out.writeUTF(c.text());
                }
            }
        }
    }
}
