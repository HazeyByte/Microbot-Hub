# IrkedMLM Humanization & Behaviour Tuning — Design

**Date:** 2026-07-14
**Plugin:** `irkedmlm` (Motherlode Mine)
**Type:** Behaviour tuning + bug fixes (no architecture change)

## Context

The plugin works end-to-end but several human-likeness/timing behaviours feel wrong,
one detection risk is serious, and the overlay lost its nugget total. This is a tuning
pass across existing files — no new subsystems.

## Decisions (defaulted while user was away — confirm before implementation)

1. **Nuggets overlay** → show **session total gained** (`gainedNuggets`, chat-parsed).
   Fixes the regression cleanly; never collapses to 0. (Owned inv+bank can be added later.)
2. **Repair scope** → **deposit-trip only**: repair is offered only when we're at/heading
   to the **lower hopper** to deposit pay-dirt and struts are down. Remove all other
   repair triggers. Keep the nearby-player / deferral check.
3. **Fast mode (human toggle OFF)** → **max speed, minimal antiban**, BUT keep the
   click-point randomization (it costs no time and addresses the detection risk).
4. **Mouse landing fix** → apply in **both modes**.

---

## The nine issues — root cause & fix

### 1. Hover fires too early / too often (should be near vein depletion)
**Now:** `MiningSession.handleMiningMouseBehaviour()` runs once at MINING entry —
45% off-screen, 35% hover-next-vein, 20% nothing — so hover happens at the *start* of a
vein, not near depletion.

**Fix:**
- Decouple hover from bout entry. Track how long the current vein has been mined
  (`subElapsed()` in MINING, and/or paydirt gained this vein).
- Hover-next-vein becomes eligible only once the vein is "nearly depleted" — proxy:
  mined for ≥ a randomized `5000–9000ms` **or** ≥3 paydirt this vein. Before that, a
  small early chance (~8%) so it "sometimes happens way before."
- Reduce overall mouse-movement frequency during a bout (lower off-screen share).
- Add the gating helper(s) to `HumanBehaviorProfile`. Hover stays human-mode-only;
  landing point is randomized (see #8).

### 2. Long wait after clicking the ladder down (both modes)
**Root cause:** `Rs2Player.isAnimating(5000)` in the floor-transition states
(`MiningSession.java:392`, `HopperSession.java:130`) blocks progression for up to **5s**
after the climb animation — independent of the human toggle. Compounded by:
- `HumanBehaviorProfile.postLadderSettleDelayMs` top-heavy (45% 1.1–1.9s, 40% 1.9–3.2s,
  15% 3.2–4.6s).
- `ladderInteractionCooldownMs` human = 1.5–2.6s.

**Fix:**
- Lower the post-climb animation guard `5000 → ~1200ms` in both transition states.
- Retune `postLadderSettleDelayMs`: mostly short with a rare long tail, e.g.
  human 60% `400–900`, 28% `900–1800`, 12% `1800–3500`; non-human `250–600`.
- Lower `ladderInteractionCooldownMs` human → ~`500–1200`.

### 3. Repairs happen when not needed
**Now:** 7+ `FIXING_WATERWHEEL` routes (post-deposit audit ×3, `determineNextStatusAfterMining`,
IDLE eval, `handleMiningStatus`, recovery ×2).

**Fix:** Single gate. Repair is considered **only** on the lower-hopper deposit trip when
struts are down and `isWaterwheelClear()` (nobody actively repairing / deferral expired).
Remove the recovery, IDLE, post-mining-sack-full, and standalone post-deposit repair routes.
Keep `RepairSession`'s nearby-player + `REPAIR_DEFER_TIMEOUT_MS` logic intact.
(Repairs are downstairs-only, and the wheel is passed en route to the lower hopper, so this
matches the physical path.)

### 4. Fast mode isn't actually fast
**Now:** Toggle OFF felt identical because the ladder animation guard (#2) dominated, not
the human pauses. Fast path already zeroes `humanPause` and skips `applyActionCooldown`.

**Fix:** With #2 fixed, additionally:
- Drop main tick interval `350 → ~250ms` when human off.
- Verify no antiban action-cooldown blocking on the fast path (it's already gated, confirm).
- Keep fast-mode session floors low (already ~90–280ms) but ensure click-point
  randomization (#8) still applies.

### 5. Overlay lost the nugget total
**Root:** `IrkedMLMOverlay.java:93` shows live `Rs2Inventory.count + Rs2Bank.count`, which
reads ~0 once nuggets are banked and the bank isn't open. The accurate `gainedNuggets`
(chat-parsed session total) is ignored.

**Fix:** Add `gainedNuggets` to `SessionSnapshot`, publish it in `updateSnapshot()`, and
render it as the "Nuggets" value.

### 6. Config descriptions
**Fix:** Rewrite `IrkedMLMConfig` item/section descriptions to be shorter and clearer;
update the Humanization description to reflect the new fast-mode and repair behaviour.

### 7. Depositing needs more randomisation
**Now:** `HopperSession` DEPOSITING/VERIFYING use fixed-ish `scheduleNextAdaptive(280,600)`
and `CLICK_THROTTLE_MS=600`.

**Fix:** Widen randomization on the deposit click gap and add an occasional short
pre-deposit "settle" pause (human mode), so the deposit cadence isn't uniform.

### 8. Mouse lands on the same area (SERIOUS — easily detectable)
**Root:** `hoverNextVein` targets the exact clickbox **center** (`MiningSession.java:234`);
`ensureMouseInGame` always returns to a fixed `200–400 × 200–400` box; repeated Mine clicks
on one vein cluster on the same pixel.

**Fix:**
- New helper `randomPointInClickbox(model)` → random interior point (inset ~15–25% from
  edges, slight center bias) instead of dead-center; use for hover.
- Vary `ensureMouseInGame` return target across a wider canvas region.
- Ensure vein Mine clicks vary their landing point per click (via the randomized point /
  naturalMouse), applied in **both** modes.

### 9. Special attack fires the instant the bar is full
**Now:** `handlePickaxeSpec()` fires as soon as energy is full and at a vein (human: only a
15% per-tick hesitation; fast: immediate).

**Fix:** After the spec bar reaches full, require a randomized "readiness" delay of
continued mining (e.g. `3–25s`, re-rolled each cycle) before speccing, in addition to the
existing hesitation. Applies in both modes so spec is never robotically instant-on-full.

---

## Files touched (all existing)

| File | Changes |
|------|---------|
| `HumanBehaviorProfile.java` | Retune ladder settle/cooldown; add hover-gating + spec-readiness helpers |
| `session/Session.java` | Lower post-climb animation guard usage; wire retuned delays |
| `session/MiningSession.java` | Hover near-depletion gating; `isAnimating(5000)`→1200; randomized clickbox point; `ensureMouseInGame` spread |
| `session/HopperSession.java` | `isAnimating(5000)`→1200; deposit-timing randomization |
| `IrkedMLMScript.java` | Consolidate repair to single deposit-trip gate; spec readiness delay; fast tick interval; publish `gainedNuggets` |
| `session/SessionSnapshot.java` | Add `gainedNuggets` field |
| `IrkedMLMOverlay.java` | Render session `gainedNuggets` for "Nuggets" |
| `IrkedMLMConfig.java` | Clearer descriptions |
| `IrkedMLMPlugin.java` | Version bump |

## Non-goals
- No new mining/pathfinding logic, no new sessions, no architecture changes.
- No changes to the sack projection / audit accounting (only its repair routing).

## Verification
- Build: `./gradlew build -PpluginList=IrkedMLMPlugin`.
- Behavioural: run under agent server; confirm (a) ladder descent resumes in <~1.5s typical,
  (b) repair only triggers on the lower-hopper deposit trip with struts down, (c) hover is
  rare and late-in-vein, (d) overlay Nuggets increments and persists, (e) spec doesn't fire
  instantly on full bar, (f) hover/click landing points vary.
