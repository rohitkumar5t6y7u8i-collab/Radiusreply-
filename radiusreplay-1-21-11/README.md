# RadiusReplay

Original client-side replay recording and playback for Minecraft Java Edition
**1.21.11** (Fabric Loader 0.19.3+, Fabric API, Java 21). Author: **RadiusXD**.

## What it does

- **Records**: player movement + camera, entities, block changes, the player's
  full inventory (hotbar, armor, offhand), health/food/air, and chat messages.
- **Plays back** with a ghost scene rebuilt on top of your live world:
  - the recorded player appears as a real player model with armor and held
    items (invisible marker entity carrying the state);
  - blocks restore exactly as they changed (visual-only updates);
  - chat reappears in its own overlay at its original timeline position with
    scroll-back support; nothing is ever sent to a server;
  - inventory overlay shows the recorded player's 41 slots with real item
    icons, counts and durability at the selected replay time.
- **Controls**: play / pause / stop, speeds 0.25x–8x, ±10s jumps, timeline
  scrubber, and three camera modes — follow, third person, detached free cam.
- **Files**: compact `.radiusreplay` format (GZIP-compressed, delta-encoded,
  versioned); corrupted or truncated files are rejected with a friendly
  message instead of crashing.

## Keys (rebindable)

| Key | Action |
| --- | ------ |
| R   | Start/stop recording |
| B   | Open replay menu |
| *(unbound)* | Play/pause |
| *(unbound)* | Toggle replay chat |
| *(unbound)* | Cycle camera |

Client-side command: `/radiusreplay record|menu|stop`.

## Performance notes

- One tick pass while recording; strictly change-only data (deltas, cached
  equipment/inventory comparisons) so idle time costs almost nothing.
- Saving runs on a daemon thread — the game never freezes when you stop.
- The HUD is plain fills and text: no textures, no framebuffers.
- Playback reuses vanilla rendering through real (client-only) proxy entities.

## Build

```bash
./gradlew build
# jar lands in build/libs/radiusreplay-1.0.0.jar
```

Requires Java 21. Drop the jar into `.minecraft/mods` together with
Fabric Loader 0.19.3+ and Fabric API.

## Legal

100% original code written for this project; no code, class names or metadata
taken from Replay Mod, Flashback or any other mod. MIT licensed. Client-side
only — it never bypasses anti-cheat or server-side protections.

## RadiusReplay viewer options
The replay viewer provides independent toggles for Replay HUD, recorded chat,
recorded player inventory/hands, recorded GUI mode, cursor and crosshair.
Camera cycling also includes a true first-person replay view.
