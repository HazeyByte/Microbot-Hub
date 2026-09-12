# irkedMLM Plugin — Audit Report

**Plugin**: Motherlode Mine automation  
**Version**: 1.0.6  
**Path**: `Microbot-Hub/src/main/java/net/runelite/client/plugins/microbot/irkedmlm/`  
**Total**: 15 source files, ~6,500 lines, 2 test files (63 lines)

---

## 1. Architecture

### Pattern: Custom Session Framework (not StateMachineScript)

The plugin implements its own `Session` abstract base (IDLE/ACTIVE/COMPLETE/FAILED states) with four concrete subclasses, driven by an orchestrator script on `ScheduledExecutorService`. This diverges from the core `StateMachineScript` framework available in Microbot.

### Layering

```
IrkedMLMPlugin (Plugin lifecycle)
  └─ IrkedMLMScript (orchestrator, 100–600ms tick)
       ├─ MiningSession  (vein selection, rockfall, path scoring)
       ├─ HopperSession  (walk + deposit pay-dirt)
       ├─ SackSession    (withdraw sack → deposit box)
       └─ RepairSession  (fetch hammer → repair struts)
```

### Event flow
1. `Plugin.startUp()` → register overlays + EventBus subscribers → `script.run()`
2. `script.run()` → `scheduleWithFixedDelay(this::executeTask, 0, tickIntervalMs, MILLISECONDS)`
3. Each tick: check login → check tools → `determineStatus()` → `dispatchByStatus()` → session tick
4. Sessions are tick-progressive (no `Thread.sleep`, no `while` loops per contract)

---

## 2. File Inventory

| File | Lines | Role |
|------|-------|------|
| `IrkedMLMPlugin.java` | 157 | Plugin entry, `@Subscribe`-equivalent events, version constant |
| `IrkedMLMScript.java` | 2,244 | **Orchestrator: status dispatch, recovery, post-deposit audit** |
| `IrkedMLMConfig.java` | 219 | 4 sections, 12 config items |
| `IrkedMLMOverlay.java` | 274 | Hand-drawn HUD with icon grid, progress bar, XP/GP rates |
| `IrkedMLMAreaOverlay.java` | 123 | Scene debug overlay for mining zones |
| `IrkedMLMMapConstants.java` | 166 | World coordinates, rockfall barriers |
| `SackTracker.java` | 53 | Pure arithmetic (projection, effective count) |
| `HumanBehaviorProfile.java` | 69 | MLM-specific timing/odds |
| `session/Session.java` | 462 | Abstract base: tick contract, ladder, floor, anti-ban |
| `session/SessionSnapshot.java` | 133 | Immutable overlay data (23 fields) |
| `session/MiningSession.java` | 1,629 | 14 sub-states, vein scoring, Bresenham path check |
| `session/HopperSession.java` | 388 | 5 sub-states, CLICK-FIRST pattern, floor correction |
| `session/SackSession.java` | 470 | 7 sub-states, withdraw/deposit loop, gem bag |
| `session/RepairSession.java` | 624 | 12 sub-states, hammer fetch, strut selection |
| `enums/MLMMiningSpot.java` | 152 | 6 spots with `WorldArea` union + preferred stand tiles |
| `enums/MLMStatus.java` | 16 | 11 status values (incl. RECOVERY) |
| `enums/MLMSackSize.java` | 22 | STANDARD / UPGRADED |
| `enums/Pickaxe.java` | 93 | Item IDs, level reqs, best-pick detection |
| `SackTrackerTest.java` | 41 | 4 unit tests for projection arithmetic |
| `IrkedMLMPluginTest.java` | 22 | 3 unit tests for nugget parsing |

---

## 3. Strengths

### 3.1 Error handling
- try/catch at every level: `executeTask` catches all exceptions, session `tick()` catches per-class
- `AtomicBoolean` guard prevents concurrent tick execution
- `InterruptedException` detection in MiningSession for graceful tick deferral
- `finally` block always updates snapshot

### 3.2 Client thread safety
- Varbit caching (1.5s TTL, forced reads for deposits + first ticks)
- XP caching (900ms TTL)
- Floor detection caching (1.2s TTL)
- Player name pushed from script → sessions (no per-session client thread fetches)

### 3.3 Human-like behavior layer
- Dedicated `HumanBehaviorProfile` class (pause delays, hesitation odds, strut-skip chance)
- Clean integration with `Rs2AntibanSettings` (action cooldown, micro-breaks, mouse movement)
- Per-activity intensity: "sometimes fast, sometimes not" with urgent bias
- Post-repair "admiration" pause, pre-deposit "inventory check" glance

### 3.4 Sack projection system
- Pre-deposit varbit capture + delta = known post-deposit count
- `getEffectiveSackCount()` self-corrects when live varbit catches up
- 30s projection window aligns with force-read logic
- `SackTracker` is pure/stateless/testable

### 3.5 Recovery/watchdog
- 4-minute status watchdog forces RECOVERY on any stuck state
- Universal hard reset after 5 RECOVERY attempts (nukes state, walks to safe, forces MINING)
- "Resolver for any spot" design philosophy

### 3.6 Combat edge cases
- Pickaxe special attack (crystal + dragon/infernal) with cooldown jitter and hesitation
- Post-spec animation wait prevents duplicate clicks
- Only activates in mining zone during MINING sub-state

---

## 4. Issues & Recommendations

### P1: Duplicated enums across packages

**Location**: `irkedmlm/enums/MLMMiningSpot.java` + `irkedmlm/enums/MLMStatus.java` vs `motherloadmine/enums/`

The irkedmlm package has its own richer versions of `MLMMiningSpot` (with `WorldArea`, `contains()`, preferred stand tiles) and `MLMStatus` (11 values vs 7). The `motherloadmine/` originals are legacy. These are shadow copies — any change to one won't affect the other, and the difference is confusing.

**Fix**: Delete `motherloadmine/enums/MLMMiningSpot.java`, `MLMStatus.java`, `MLMMiningSpotList.java` if irkedmlm is the sole consumer. Or move shared enums to a common package.

### P2: IrkedMLMScript.java is 2,244 lines

The orchestrator does too much:
- `handlePostDepositAudit()` (~160 lines) has deeply nested decision trees
- `handleRecoveryStatus()` (~130 lines) mixes retry logic, safe walking, and routing
- `handleEmptySackStatus()` and `handleDepositHopperStatus()` each ~50+ lines
- Status determination logic is duplicated between `determineStatus()` and `determineNextStatusAfterMining()`

**Fix**: Extract post-deposit audit into a dedicated class. Consolidate status determination into a single path. Consider splitting script into sub-orchestrators (MiningOrchestrator, DepositOrchestrator, etc.).

### P3: MiningSession.java is 1,629 lines

The largest session with 14 sub-states. The `tickMiningInternal()` switch has ~500 lines. Vein selection, scoring, and path logic are all in one class.

**Fix**: Extract vein scoring into a `VeinScorer` class. Extract path/barrier logic into a `RockfallPathChecker`. Keep MiningSession as pure sub-state machine.

### P4: No tests for MiningSession or other sessions

Only `SackTrackerTest` (4 tests) and `IrkedMLMPluginTest` (3 tests) exist. The complex vein scoring, rockfall path checking, and sub-state transitions have zero test coverage.

**Fix**: Add parameterized tests for `scoreVein()`, `pathCrossesBarriers()`, cluster bonus, failure blacklist expiry, and sub-state transition edge cases.

### P5: Bresenham path check is inaccurate

`pathCrossesTile()` (MiningSession:1458) uses Bresenham line-rasterization to check if a rockfall blocks the path from player to vein. This ignores OSRS wall/collision data — a wall that doesn't cross the Bresenham line can still block movement.

**Fix**: Use `Rs2Tile.getNearestWalkableTile()` or check actual tile reachability via the scene/game collision map instead of geometric line crossing. If Bresenham is kept, add a `ponytail:` comment naming the ceiling.

### P6: Static string resources vs ItemID/ObjectID

Hardcoded strings for pickaxe names (`"dragon pickaxe"`, `"infernal pickaxe"`) in `DRAGON_INFERNAL_PICKAXES` array. The `Pickaxe` enum already has `itemName` fields. For consistency these should reference the enum.

Also in `hasHammer()` and `tickCleanup()`: `"hammer"` is used as a string, could use `ItemID.HAMMER` directly.

### P7: Manual EventBus registration

`IrkedMLMPlugin` uses `eventBus.register(ChatMessage.class, this::onChatMessage, 0.0f)` instead of `@Subscribe`. This is documented as intentional (avoids LambdaConversionException on some builds) but it means:
- Two subscriber fields to manage (`chatMessageSubscriber`, `varbitChangedSubscriber`)
- Manual cleanup in `shutDown()`
- Priority hardcoded at 0.0f (can't be adjusted per-module)

If the LambdaConversionException is resolved in current runelite, switch to `@Subscribe`.

### P8: Deprecated methods in MLMMiningSpot

`containsInArea()` and `containsInWorldArea()` are `@Deprecated` but still present. Remove them.

### P9: Recovery logic complexity

Three overlapping mechanisms handle "stuck":
1. `RECOVERY` status (driven by `handleRecoveryStatus`)
2. Execution watchdog (4 minutes in same status)
3. Script-level `recursion` guard (`AtomicBoolean executing`)

The RECOVERY handler alone has 6 distinct branches based on attempt count + inventory state. This is hard to test and harder to debug.

### P10: Missing Lombok on Session fields

`Session.java` has hand-written getters (`isIdle()`, `isActive()`, etc.) when `@Getter` on the `state` field would generate them. Minor consistency issue.

---

## 5. Patterns to Preserve

| Pattern | Why |
|---------|-----|
| AtomicReference&lt;SessionSnapshot&gt; | Thread-safe overlay data without locks |
| Varbit + XP + floor caching | Prevents startup TimeoutExceptions |
| `beginFloorCorrectionIfNeeded()` shared guard | Avoids copy-paste floor checks in HopperSession |
| `HumanBehaviorProfile` static class | Testable, centralized, forces humanization decisions through one API |
| `scheduleNext()` / `subElapsed()` timing | Prevents action spam on executor ticks |
| `SackTracker` pure function pattern | Testable with zero mocking |

---

## 6. Summary

The plugin is **well-engineered** with robust error handling, thoughtful caching, and a human-like behavior layer. The custom Session framework is a clean alternative to StateMachineScript for this use case. Main concerns are **file size** (the two largest files account for 60% of all code), **enum duplication** (conflicts with motherloadmine package), **test coverage** (only pure functions are tested), and **overly complex recovery logic**. The Bresenham path check is a correctness concern — replace with actual tile reachability checks if collisions cause issues.
