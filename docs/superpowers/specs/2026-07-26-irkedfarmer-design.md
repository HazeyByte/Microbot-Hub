# irkedFarmer — AIO Farming Plugin Design

**Date:** 2026-07-26
**Author:** irkedMATT
**Status:** Design — pending review
**Supersedes:** `farmtreerun`, `herbrun`, `birdhouseruns` (three standalone plugins → one AIO)

---

## 1. Goal

One All-In-One farming plugin, `irkedFarmer`, that behaves like an experienced player: it
builds a **queue of independent farming runs** from the categories the user enables, prepares
an inventory for **one run at a time** (never overflowing 28 slots), executes each run with
real recovery, banks between runs, and reports what it did and why.

It absorbs and replaces three existing plugins:

| Source plugin | Quality today | Fate in irkedFarmer |
|---|---|---|
| `farmtreerun` | Legacy god-script (1,145 lines, linear 22-state enum, flat inventory, `System.out` logging, hardcoded routes) | **True rewrite** into Tree/Fruit/Hardwood tasks |
| `herbrun` | Modern (FarmingWorld prediction, `Rs2InventorySetup`, confirmed transitions, leprechaun noting) | **Wrapped** as `HerbRunTask` — reuse logic, adapt to task interface |
| `birdhouseruns` | Modern (varp state predicates, stall timeouts, structured logging, quest gating) | **Wrapped** as `BirdhouseRunTask` — reuse logic, adapt to task interface |

**Design principle:** rewrite only what is broken (the tree run). Wrap what already works. Do
not regress two functioning plugins in the name of uniformity.

---

## 2. The core problem being solved

The legacy tree runner builds **one flat inventory for every enabled category at once**
(`FarmTreeRunScript.bank()`): coins + tools + energy + compost + pendant + cape + necklace +
up to 8 tree saplings + crystal sapling + up to 7 fruit saplings + up to 4 hard saplings + 3
protection stacks + teleports + 5 rune types. With Trees+Fruit+Hardwood all enabled (all
default `true`) that exceeds 28 slots, with no capacity check. It also required protection
payment items the user never knowingly asked for (Magic/Dragonfruit pay in **coconuts**, and
`protectTrees()` defaulted `true`) and aborted the whole run when they were missing.

An experienced player never preps everything at once. They think: *"tree run first → bank →
fruit trees → bank → hardwood."* irkedFarmer makes that the execution model.

**Interim fixes already shipped** to legacy `farmtreerun` v1.3.0 (protection non-fatal +
logged, best-effort payment, slot-estimate guard) buy time; this rewrite makes them
structural.

---

## 3. Architecture

### 3.1 Task queue model

```
IrkedFarmerConfig  ──►  TaskScheduler
                          │  queue = [ enabled tasks, in efficient order ]
                          │
                          ▼   for each task:
             ┌────────────────────────────────────┐
             │  task.isDue()      → skip if nothing ready (FarmingHandler prediction)
             │  plan = task.plan()→ InventoryPlan (validated ≤ 28 slots)
             │  if !plan.feasible → report & skip (re-plan / advise, never overflow)
             │  BankService.prepare(plan)  → deposit-all, withdraw, VERIFY
             │  result = task.execute()    → run patches, own recovery
             │  report(result)             → structured, explains decisions
             └────────────────────────────────────┘
                          ▼
                    finish → bank → stop
```

### 3.2 The `FarmingTask` interface

```java
public interface FarmingTask {
    String name();                       // "Tree run", "Herb run", ...
    boolean isEnabled(IrkedFarmerConfig cfg);
    boolean isDue();                     // any patch not still GROWING (prediction)
    InventoryPlan plan();                // exactly what THIS run needs, self-validating
    TaskResult execute();                // runs the whole run; owns its recovery
}
```

Each task is understandable and testable in isolation: you can answer *what it does, what it
needs, what it depends on* without reading the others. This is also what makes future tasks
(Hespori, Calquat, Spirit tree, Celastrus, Redwood, Crystal tree) **drop-in** — implement the
interface, add a config toggle, register it. No existing task changes.

### 3.3 Concrete tasks (v1 scope)

- `TreeRunTask` — regular tree patches (Gnome, Falador, Lumbridge, Varrock, Taverley, Farming Guild, Auburnvale) + Prifddinas crystal patch.
- `FruitTreeRunTask` — fruit patches (Gnome, Tree Gnome Village, Brimhaven, Catherby, Farming Guild, Lletya, Kastori).
- `HardwoodRunTask` — Fossil Island ×3 + Avium Savannah.
- `HerbRunTask` — wraps herbrun (herb + flower + allotment phases).
- `BirdhouseRunTask` — wraps birdhouseruns (4 Fossil Island houses).

### 3.4 Shared services (extracted, single-responsibility)

| Service | Responsibility | Seeded from |
|---|---|---|
| `InventoryPlanner` | Build + **validate** an `InventoryPlan`; correct slot counting (noted/stackable = 1 slot, else qty); merge duplicates; account for equipped items | new (fixes legacy bug) |
| `BankService` | deposit-all-except, withdraw plan, **verify every withdrawal**, recover on shortfall, note/unnote toggles | legacy `bank()`, hardened |
| `PatchInteractor` | rake / clear / plant / compost / pick / pay-gardener with **confirmed** state transitions and timeouts | herbrun + tree run generalized |
| `PatchPredictor` | wrap `FarmingWorld` + `FarmingHandler` to answer "is this patch due / growing / diseased" | herbrun (already uses it) |
| `LeprechaunService` | note produce for space, withdraw compost | herbrun `noteProduceViaLeprechaun` |
| `TeleportProvider` | resolve the best available teleport to a target from the player's unlocks; fallbacks | new (Phase 5) |
| `FarmLog` | structured decision logging (slf4j) — every decision explains itself | replaces `System.out.println` |

### 3.5 Inventory planning (kills overflow structurally)

`InventoryPlan` is built per task and self-validates against 28 slots **before** banking.
Correct slot accounting:
- noted item → 1 slot
- stackable item (coins, runes, tabs) → 1 slot
- unstackable (saplings, tools) → 1 slot per unit
- equipped items (graceful, skills necklace, cape) → 0 inventory slots

If a plan can't fit, the task **re-plans** (e.g. split a large fruit run, reuse a teleport,
withdraw noted payment) or reports precisely what's infeasible — it never blindly withdraws.

### 3.6 Protection = pure user choice

Payment items are requested **only** when the matching protect toggle is on, are always
optional (never abort the run), and a missing payment logs *"…planted UNPROTECTED"* and
continues. No hardcoded coconut requirement, no protection-on-by-default surprise. (Confirmed
user decision: unprotected planting on missing payment is acceptable; the user owns that
choice.)

### 3.7 Config

One `IrkedFarmerConfig` (`@ConfigGroup("irkedfarmer")` — **not** the legacy `"example"` group,
which caused persisted-state surprises). Sections: General, Trees, Fruit Trees, Hardwood,
Herbs, Birdhouses. Each activity: enable toggle → patch toggles → sapling/seed selection →
compost → protection. Optional `Rs2InventorySetup` per activity (herb/birdhouse already
support named setups; extend to trees). Task selection drives the queue.

---

## 4. What we deliberately do NOT build (YAGNI)

- **Global route optimisation (TSP).** v1 keeps each task's static patch order (already
  near-optimal — these are the community-standard routes). `TeleportProvider` gives per-leg
  teleport selection from unlocks; full dynamic routing is a later project behind that seam.
- **Speculative farming tasks** (Hespori, Calquat, Spirit, Celastrus, Redwood). The interface
  makes them cheap to add later; we don't build them until asked.
- **Compost/protection micro-optimisation** beyond what the sources already do.

---

## 5. Phased implementation

Each phase ends with a **green build** and a **verification step** (agent server or manual).
Early phases deliver working value; the risky consolidation of already-working plugins comes
*after* the architecture is proven on the tree run.

### Phase 0 — Scaffolding & shared substrate
- New `irkedfarmer` package; `IRKED` prefix in `PluginConstants`.
- Define `FarmingTask`, `TaskScheduler`, `TaskResult`, `InventoryPlan`, `FarmLog`.
- `IrkedFarmerPlugin` + `IrkedFarmerConfig` skeleton + overlay.
- Builds; scheduler logs an (empty) queue. **No behavior yet.**

### Phase 1 — Tree / Fruit / Hardwood tasks (the real rewrite)
- `InventoryPlanner`, `BankService`, `PatchInteractor`, `LeprechaunService`, `PatchPredictor`.
- `TreeRunTask`, `FruitTreeRunTask`, `HardwoodRunTask` on those services.
- Migrate off deprecated `ItemID` (`COINS_995` etc.) to `gameval.ItemID` per no-deprecated policy.
- **Verify:** each task runs solo end-to-end; no overflow; protection optional.

### Phase 2 — Scheduler integration
- Wire the three tree tasks into the queue with **bank-between-runs**.
- **Verify:** trees → bank → fruit → bank → hardwood, single session, no overflow, correct order.

### Phase 3 — Absorb Herb run
- `HerbRunTask` wrapping herbrun's existing logic behind the shared interfaces (reuse
  FarmingWorld prediction, leprechaun noting, phase handling).
- **Verify:** parity with standalone herbrun (herb + flower + allotment).

### Phase 4 — Absorb Birdhouse run
- `BirdhouseRunTask` wrapping birdhouseruns logic (varp predicates, quest gating, stall
  timeout, on-island teleport disabling).
- **Verify:** parity with standalone birdhouseruns.

### Phase 5 — Routing / teleport intelligence
- `TeleportProvider`: pick best available teleport per leg from unlocks (diary cloaks,
  jewellery, spellbook, POH, fairy rings, spirit trees), with graceful fallback.
- **Verify:** correct teleport chosen across differing unlock sets; fallback when preferred unavailable.

### Phase 6 — Humanization & polish
- Antiban integration, natural delays/hesitation, overlay, full structured decision logging,
  README + docs + assets.

### Phase 7 — Cleanup & migration
- Remove (or leave thin deprecation shims for) `farmtreerun`, `herbrun`, `birdhouseruns`.
- Config migration note for users; final build + `plugins.json` regen.

---

## 6. Risks & mitigations

| Risk | Mitigation |
|---|---|
| Regressing working herb/birdhouse plugins | Wrap, don't rewrite; verify parity per phase; keep originals until parity confirmed |
| Instanced-region coordinate mismatches (Prifddinas, Fossil Island) | Follow `docs/PLUGIN_DEBUGGING_NOTES.md`; reuse birdhouse's proven Fossil-Island handling |
| Big-bang merge | Strict phasing; each phase independently shippable and verified |
| Config migration surprises | New `irkedfarmer` group; document that settings won't carry over from old plugins |
| Deprecated API creep | Standardise on `gameval.ItemID`/`ObjectID`; no deprecated APIs per project policy |

---

## 7. Open questions for review

1. **Old plugins:** hard-remove after parity, or leave thin "moved to irkedFarmer" shims for a release?
2. **Herb/Fruit/Tree "due" gating:** should the AIO auto-skip a run whose patches are all still
   growing (via prediction), or always attempt enabled runs? (Recommend: auto-skip, with an override.)
3. **Single combined bank trip vs. bank-between-every-task:** always bank between tasks (simple,
   safe), or merge adjacent same-region prep? (Recommend: bank between tasks in v1; optimise later.)
