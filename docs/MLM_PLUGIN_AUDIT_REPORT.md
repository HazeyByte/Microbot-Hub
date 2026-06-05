# Motherlode Mine Plugin — Audit Report (v2.1.42)

**Date:** 2026-06-04  
**Scope:** `src/main/java/net/runelite/client/plugins/microbot/motherloadmine/`  
**Build:** `build/libs/IrkedMLMPlugin-2.1.42.jar` — **BUILD SUCCESSFUL**

---

## Summary

A full audit of the Motherlode Mine (MLM) Hub plugin was performed against Microbot-Hub architecture docs, OSRS MLM mechanics, and the current session-based implementation (`MotherloadMineScript` orchestrator + `MiningSession`, `HopperSession`, `SackSession`, `RepairSession`).

**v2.1.42** addresses the highest-impact reliability bugs: incorrect sack projection when varbit lags, antiban freezing active sessions, status overrides during `MINING`, hopper verify/transition loops, stale rockfall memory, and upstairs mining flow (skip walk phase after ladder — from prior 2.1.41 work, retained).

---

## Phase 1 — Repository & Framework Integration

| Area | Finding |
|------|---------|
| **Build** | Per-plugin source set; `build -PpluginList=IrkedMLMPlugin` |
| **Lifecycle** | `IrkedMLMPlugin` → `MotherloadMineScript.run()` → scheduled `executeTask()` |
| **Sessions** | Sub-state machines in `session/*`; orchestrator ticks active sessions before dispatch |
| **Threading** | Script executor for logic; `Microbot.getClientThread()` for varbits/widgets; `@Subscribe` on plugin for chat/varbit |
| **Sack signal** | Varbit **5558** + chat phrases (`sack is full`, `sack will be full`, etc.) |
| **Debug** | Agent Server `127.0.0.1:8081`, `MLM_TESTING_PROTOCOL.md`, plugin `docs/README.md` |

The MLM plugin follows Hub conventions: `@PluginDescriptor`, version field, config `@Provides`, overlay in `startUp`/`shutDown`, and no blocking client APIs on the script thread without `invoke()`.

---

## Phase 2 — OSRS Motherlode Mine Mechanics (Relevant to Bot)

| Mechanic | Bot implication |
|----------|-----------------|
| **Pay-dirt mining** | Inventory fills with pay-dirt; veins deplete and respawn |
| **Hopper** | Deposits pay-dirt; processing is not instant; sack varbit updates with lag |
| **Sack** | Capacity 108 / 189 (upgraded); hopper **rejects** when sack full |
| **Partial deposit** | Near-cap deposits may leave pay-dirt in inventory; must audit inv delta |
| **Upper floor** | 57 Mining; compact chambers; separate hopper; ladder required from lower |
| **Water wheels / struts** | Broken struts reduce flow; repair after deposit (plugin policy) |
| **Rockfalls** | Block paths; may need clearing (sub-state exists but not wired) |
| **Golden nuggets** | Chat-tracked; not auto-deposited |

---

## Phase 3 — Issues Identified

### Critical (fixed in 2.1.42 unless noted)

| Issue | Root cause | Impact |
|-------|------------|--------|
| **Varbit-0 fallback used batch size as sack total** | `currentSackCount()` returned `payDirtJustDeposited` (e.g. 28) instead of `sackValueKnownAfterLastDeposit` | Under-estimated fullness → continued mining/deposit into full sack |
| **Antiban cooldown skipped entire tick** | Early `return` before session ticks | Mining/hopper/sack sessions frozen mid-animation |
| **`determineStatus()` during MINING** | No early return for `MINING` | Status could flip to `EMPTY_SACK`/`DEPOSIT_HOPPER` while mining session active |
| **Hopper verify retry not counted** | `depositRetryCount` not incremented on verify retry | Unbounded deposit/verify loops |

### High (fixed or mitigated)

| Issue | Status |
|-------|--------|
| Hopper `TRANSITIONING_FLOOR` no stall timeout | **Fixed** — 20s → `FAILED` |
| `SackSession` used raw `currentSackCount()` | **Fixed** — uses `getEffectiveSackCount()` |
| Varbit cache fields not volatile | **Fixed** — `volatile` on `lastVarbitValue` / `lastVarbitReadMs` |
| Rockfall tiles never pruned | **Fixed** — `retainAll` + `addAll` on active scene tiles |
| Upper floor walk loop after ladder | **Fixed (2.1.41)** — `shouldSkipSpotWalk`, `isWrongFloor` floor-only |
| Post-sack walk without ladder session | **Open** — still walks to ladder bottom; mining session handles floor on next `begin()` |

### Medium (documented / partial)

| Issue | Notes |
|-------|-------|
| `CLEARING_OBSTACLE` never entered | Dead path; rockfalls only avoided in pathing |
| `blacklistedCrates` unused | Dead field on plugin |
| Repair requires 2 broken struts + clear wheel | May skip single-strut repair on post-deposit path |
| `Thread.sleep` in drop/spec on executor | Blocks whole MLM tick when human-like on |
| `EAST_UPPER` strict mesh | Veins outside mesh not selected — config/data issue |

### Low

| Issue | Notes |
|-------|-------|
| `queryBrokenStrutCount` used `.toList().size()` | **Fixed** — `.count()` |
| `MOCROSOFT` typo in descriptor | Cosmetic |
| `Pickaxe` enum underused | Generic `"pickaxe"` check used |

---

## Phase 4 — Changes Implemented (v2.1.42)

### `MotherloadMineScript.java`

1. **Antiban dispatch pause** — `antibanDispatchPause` skips `determineStatus()` and `dispatchByStatus()` only; **session ticks always run**; return after session block if paused.
2. **`determineStatus()`** — `case MINING: return;` prevents mid-mining status overrides.
3. **`currentSackCount()`** — Single fallback: projected **total** = `sackValueKnownAfterLastDeposit` or `lastPreDepositSackCount + payDirtJustDeposited` (not batch size alone).
4. **`SackSession.tick`** — Passes `getEffectiveSackCount()` for withdraw progress.
5. **`queryBrokenStrutCount()`** — Uses `.count()` instead of allocating a list.

### `HopperSession.java`

1. **`TRANSITIONING_FLOOR`** — `invalidateUpperFloorCache()` before/after `ensureFloor`; **20s stall** → `FAILED`.
2. **`VERIFYING`** — Retries use `incrementAndCheckRetryLimit()` (max 3) instead of hard-coded `depositRetryCount >= 1`.

### `MiningSession.java` (includes 2.1.41 upstairs behavior)

1. **`shouldSkipSpotWalk` / `miningEntrySubState`** — Upper floor → `SELECTED` directly (no walk-to-anchor phase).
2. **`isWrongFloor`** — Floor height only for upstairs/downstairs (not anchor proximity).
3. **`updateRememberedRockfalls`** — Prunes despawned rockfall tiles each tick.
4. **Vein selection** — Upstairs ignores 12-tile anchor cap on primary/fallback paths.

### `Session.java`

- Removed unused `sleepUntil` import (if present).

### `IrkedMLMPlugin.java`

- Version **2.1.42**.

---

## Phase 5 — Validation Results

| Check | Result |
|-------|--------|
| `./gradlew build -PpluginList=IrkedMLMPlugin` | **SUCCESS** |
| JAR output | `build/libs/IrkedMLMPlugin-2.1.42.jar` |
| Compilation errors | None |
| Warnings | Deprecation/unchecked (pre-existing Microbot APIs) |

**Manual testing recommended** (see `docs/MLM_TESTING_PROTOCOL.md`):

- Start downstairs, **EAST_UPPER**, human-like off → one ladder, then Selecting/Clicking vein (no walk loop).
- Fill sack → deposit → verify projection does not under-count → `EMPTY_SACK`.
- Partial hopper near cap → post-deposit audit drops residual pay-dirt.
- Antiban cooldown during mining → session should still progress.

---

## Future Improvements

### Reliability

- Wire `CLEARING_OBSTACLE` when path blocked by rockfall, or remove dead sub-state.
- Post-sack: explicit `ensureFloor` before `MINING` when spot is upstairs (avoid brief wrong-floor mining).
- Replace `Thread.sleep` in pay-dirt drop with tick-scheduled delays.
- Populate or remove `blacklistedCrates` for repair crate search.

### Performance

- Reduce force varbit reads once stable; keep projection window aligned with `SACK_PROJECTION_WINDOW_MS`.
- Overlay: ensure `SessionSnapshot` avoids client-thread work (already cached in script).

### Architecture

- Extract sack projection into small `SackProjection` helper (testable unit).
- Unify floor transition entry (`miningEntrySubState` pattern) for hopper/sack return paths.

### Testing

- `ScriptLifecycleTest`-style flow: login → start MLM → poll until deposit → assert sack projection fields via debug snapshot endpoint.
- Table-driven tests for `getEffectiveSackCount()` math (no client required).

### Features

- Expand `EAST_UPPER` mesh or enable adjacency buffer like `WEST_UPPER`.
- Config: repair at 1 strut when human-like off.
- Optional rockfall auto-clear on upper (compact area).

---

## File Reference

| Component | Role |
|-----------|------|
| `MotherloadMineScript.java` | Status machine, sack projection, session orchestration |
| `MiningSession.java` | Mine loop, veins, upstairs skip-walk |
| `HopperSession.java` | Deposit pay-dirt, floor-aware hopper |
| `SackSession.java` | Empty sack, deposit box, gem bag |
| `RepairSession.java` | Struts, hammer, wheels |
| `Session.java` | Floor cache, ladder, delays |
| `MLMMiningSpot.java` | Areas, meshes, anchors |
| `MotherloadMineConfig.java` | User settings |

---

*Report generated as part of the MLM audit task. Deploy **IrkedMLMPlugin-2.1.42.jar** for all fixes above.*
