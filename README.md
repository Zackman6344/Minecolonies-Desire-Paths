# Colony Paths — spike

Experimental Minecolonies addon that learns where citizens walk most and (eventually) lets the
player commission a builder to lay an actual path along those routes. This repo is a **throwaway
spike** — three small validations before committing to the design:

- **(a)** Sample citizen positions cheaply into a per-chunk grid. ✅ implemented (`CitizenTracker`, `HeatMap`)
- **(b)** Dump the grid as ASCII so we can see if it looks like anything. ✅ implemented (`/colonypaths heatmap`)
- **(c)** Place a single block via a Minecolonies builder work order. ⏳ deferred to Phase 2 — needs source-reading of Minecolonies' `WorkOrderDecoration` / blueprint system.

If (a) and (b) work end-to-end, we'll know the position-tracking pipeline is sound. If (c) turns out gnarly, the fallback is server-side direct block placement on player approval (less elegant, ships faster).

## Target

| | |
|---|---|
| Minecraft | 1.21.1 |
| Loader | NeoForge `21.1.230` |
| Java | 21 |
| Minecolonies | `1.1.1300-1.21.1` (release, Apr 2026) — soft dep, marked optional |

## Build / run

JDK 21 must be on Gradle's toolchain path. On this machine it's at `C:\Program Files\Java\jdk-21.0.11` — Gradle's auto-discovery should find it; if not, the foojay resolver in `settings.gradle` will download one. `JAVA_HOME` does **not** need to be set.

```powershell
# First sync — downloads NeoForge, Parchment mappings, Minecolonies. Takes 5-10 min on first run.
.\gradlew.bat --refresh-dependencies

# Compile only (faster sanity check)
.\gradlew.bat compileJava

# Launch a dev client with the mod and Minecolonies loaded
.\gradlew.bat runClient

# Launch a dev dedicated server
.\gradlew.bat runServer
```

In-game:

```
/colonypaths render [radius]               # writes a PNG (heatmap + building markers + your position) to <world>/colonypaths/. Default radius 64. THIS IS THE RECOMMENDED VIEW.
/colonypaths heatmap [radius]              # ASCII fallback — dumps grid to logs/latest.log
/colonypaths stats                         # samples, chunks tracked, peak count
/colonypaths reset                         # clears the grid
/colonypaths builders                      # lists every BuildingBuilder in every colony (Phase 2 enumeration check)
/colonypaths buildings                     # lists ALL buildings (schematic name + level + position). Use this to cross-check what's on the rendered map.
/colonypaths seed [radius] [hits]          # stuffs synthetic gaussian samples around you (default radius=8, hits=200) — for testing the viz without waiting for citizens
```

**Citizen tracking is always on** — no command to start/stop it. The tracker fires every 20 server ticks (1s) and samples every loaded citizen in every colony. To see real data accumulate, found a colony and let the citizens go about their day for a few minutes, then `/colonypaths render`. To see the visualization immediately with fake data, use `/colonypaths seed` first.

### PNG output

`/colonypaths render` writes a top-down view to `<world-save>/colonypaths/heatmap_<x>_<z>_r<radius>_<timestamp>.png`. The chat message prints the absolute path so you can copy-paste it into any image viewer. Open it in your OS file explorer or drag into a browser tab.

Visual key:
- Dark gray background with faint chunk lines (every 16 blocks).
- Heat gradient: invisible (no data) → dark red → red → orange → yellow → white (peak).
- Gold squares: builder huts. Light blue squares: other Minecolonies buildings.
- Green crosshair: your position when you ran the command (image is centered here).

Pixel scale auto-adjusts to target ~512px output — radius 64 → ~3px/block, radius 256 → 2px/block.

The ASCII grid is logged to the server console (monospaced) because Minecraft chat font isn't fixed-width. Glyphs: `.` zero, `·` very low, `:` low, `o` med, `O` high, `#` peak.

## What's verified

`gradlew compileJava` succeeds against Minecolonies `1.1.1300-1.21.1` from `ldtteam.jfrog.io/ldtteam/modding/`. This confirms:

- Maven coord format is `<modver>-<mcver>` (no `-RELEASE` suffix), e.g. `1.1.1300-1.21.1`.
- These Minecolonies API symbols all exist and have the shapes we expected:
  - `IMinecoloniesAPI.getInstance().getColonyManager()`
  - `IColonyManager.getAllColonies()` → `List<IColony>`
  - `ICitizenData.getEntity()` → `Optional<AbstractEntityCitizen>`
  - `AbstractEntityCitizen.blockPosition()` (vanilla MC inherited)
- Structurize was pulled as a transitive dep — no extra declaration needed.

What's **not** yet verified: actually running the mod and observing the heatmap fill in. Next step is `gradlew runClient` to launch a dev MC instance with Minecolonies loaded, found a colony, and watch the counts climb via `/colonypaths stats` and `/colonypaths heatmap`.

## Phase 2 — builder work-order placement (research)

Verdict: **feasible.** Reading Minecolonies' own [`DecorationBuildRequestMessage`](https://github.com/ldtteam/minecolonies/blob/version/main/src/main/java/com/minecolonies/core/network/messages/server/DecorationBuildRequestMessage.java), the recipe to submit a structure-placement task to a colony builder is:

```java
StructurePacks.getBlueprintFuture(packName, path).thenAccept(blueprint -> {
    WorkOrderDecoration order = WorkOrderDecoration.create(
        WorkOrderType.BUILD, packName, path, displayName,
        pos, rotation, mirror, /*currentLevel*/ 0);
    order.setBlueprint(blueprint, colony.getWorld());
    order.setClaimedBy(builderHutPos);            // optional — assigns a specific builder
    colony.getWorkManager().addWorkOrder(order, false);
});
```

**Important caveat — blueprints must come from a registered StructurePack on disk.** The builder AI reloads the blueprint by `(packName, path)` on its own ticks, so even though `Blueprint` has public constructors and `BlueprintUtil.createBlueprint(world, ...)` can capture one from a live level, you can't submit a purely in-memory blueprint through this path. Two options:

1. **Ship a tiny `.blueprint` file as a packaged resource**, extract it to `<world>/colonypaths-pack/` on first server start, then call `StructurePacks.discoverPackAtPath(...)` to register the pack. Refer to it later by `(packName, path)`.
2. **Generate at runtime**: place the block in a throwaway corner of the world, call `BlueprintUtil.createBlueprint(...)` to capture it, write the bytes to disk in the registered pack folder, then proceed. Avoids any pre-authored asset but adds the "set up a hidden capture spot" code.

The fallback if the work-order path becomes too tangled: place blocks directly server-side on player approval (skips the builder NPC entirely; loses the "watch the builder build it" vibe but ships fast).

`/colonypaths builders` already validates the colony/builder enumeration half of the recipe.

## What's deliberately NOT in this spike

- **Persistence.** Heatmap is in-memory; reset on server restart. SavedData per dimension is a future step.
- **Multi-dimension.** Grid keys are `(chunkX, chunkZ)` only; colonies in different dims at the same chunk coords would collide.
- **Visual overlay.** No in-world rendering yet — ASCII dump only.
- **Path extraction.** No algorithm to turn density data into a line of blocks. This is the second hard part flagged in design discussion.
- **Phase 2 implementation.** Research only — see recipe above. Needs blueprint asset + StructurePack registration code.

## File map

```
src/main/java/dev/colonypaths/
  ColonyPaths.java              — @Mod entry point
  heatmap/HeatMap.java          — per-chunk 16x16 int grid, singleton
  heatmap/CitizenTracker.java   — ServerTickEvent.Post listener, samples every 20 ticks
  command/HeatmapCommand.java   — /colonypaths brigadier command
```
