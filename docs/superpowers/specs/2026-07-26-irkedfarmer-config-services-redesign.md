# irkedFarmer — Config UX + Smart Services Redesign

**Date:** 2026-07-26
**Author:** irkedMATT
**Status:** Design — approved, pending spec review
**Supersedes:** the current flat `irkedfarmer` config (unreleased/untested — free to replace)

---

## 1. Goal

A cohesive configuration where **a new user can set up a farming run in under a minute without reading
docs**. The config describes **what** the user wants; the plugin's services decide **how** to achieve it.

Two acceptance criteria from Matt:
1. The user can still do **everything** (no capability lost vs. today).
2. It is **easy to understand and follow**, and the **backend (non-config code) actually works**.

Scope: the **6 activities that exist today** (Tree, Fruit, Hardwood, Herb, Allotment, Flower, Birdhouse),
architected so new farm types drop in without config churn. New farm types and a travel-time estimator
are explicitly **out of scope**.

---

## 2. Principles

- **Config = declaration of intent. Services = mechanism.** The UI never asks *how* (routes, tool IDs,
  bank names, teleport choice) — only *what* (which crops, which patches, which seed).
- **One setting, one place.** No duplicated/contradictory options. If two settings affect the same
  behaviour they are merged or one is removed.
- **Reveal on demand.** A component's settings are hidden until that component is enabled.
- **Intelligent defaults.** Enabling a component with all defaults produces a working run.
- **Lean on Microbot.** Routing = `Rs2Walker`; banking = `Rs2Bank`; state = `Rs2Inventory`/`Rs2Equipment`/
  `Rs2Player`; discovery = Queryable caches; humanisation = `Rs2Antiban`. No reinvented subsystems.

---

## 3. Config layout

**CORRECTION (2026-07-27):** `@ConfigItem` in this RuneLite/Microbot version has no `unhide`/conditional-reveal
attribute (verified against `ConfigItem.java` — only `position/keyName/name/description/hidden/warning/secret/section`).
`hidden()` is a static, one-way "never render" flag with no listener to reveal it later; no Hub plugin hand-rolls a
custom panel for this. The reveal mechanism is therefore RuneLite's real, already-supported one: each component's
section is a normal `@ConfigSection` with `closedByDefault = !<component toggle default>` — collapsed for
disabled/niche components, expanded for the common ones, and the user can always expand any section manually
regardless of the toggle. This is weaker than true reveal-on-demand (a disabled component's settings are
collapsed, not hidden) but is the honest capability of the framework and matches every existing Hub plugin's
config UX.

### 3.1 Run Builder (`section` position 0)
A **Preset** selector at the top (`Full run / Herbs / Trees / Fruit / Hardwood / Birdhouses / Custom`)
pre-fills the component toggles for one-click common setups (research: presets are the standout UX of
established AIO farmers). Below it, one boolean per existing component: `trees`, `fruitTrees`, `hardwood`,
`herbs`, `allotments`, `flowers`, `birdhouses`. (Allotments/Flowers are sub-parts of the herb-run trip but
presented as first-class components; the herb task honours their toggles.) New components append here later.

### 3.2 Patch Selection (position 1)
Per component, patch toggles, grouped in that component's own collapsible section (collapsed by default
unless the component is on). E.g. herb patches (Falador, Catherby, Ardougne, Farming Guild, Hosidius,
Morytania, Troll Stronghold, Weiss, Varlamore) live in the Herbs section. The user picks **where**; the
plugin picks the **order** (see TravelService). Default: the commonly-used patches on, niche ones
(Weiss/Trollheim/Harmony) off.

### 3.3 Seeds (position 2)
Per component, **one enum dropdown** in that component's section, default **"Best available"**
(auto-selects the highest-level crop the bank can supply). No item IDs, no bank names. Categories: herb
seed, tree sapling, fruit sapling, hardwood sapling, allotment seed, flower seed, birdhouse log.

### 3.4 Compost (position 3)
**Single** selector: `None / Regular / Super / Ultra / Bottomless bucket`. Plus "Bottomless: auto-refill".
The plugin applies compost **intelligently per patch**: trees get compost only when *not* being protected
(protection eliminates disease; compost adds no tree yield); herbs/allotments always get it (yield gain).
This replaces both the current "Tree compost" and "Herb compost" settings — no duplicates.

### 3.5 Payments (position 4)
`Pay farmers automatically` (protection) · `Skip if payment missing` (best-effort, never abort) ·
`Withdraw payment items`. Replaces the per-section `protect` toggles. PaymentService resolves each crop's
required payment item automatically.

### 3.6 Travel (position 5)
`Use best teleport` (delegate to Rs2Walker transports) · `Walker fallback` · `Avoid expensive teleports` ·
`Minimum run energy` (int) · `Use stamina potions` · `Restore run energy`. No per-teleport toggles.

### 3.7 Equipment (position 6)
`Auto-detect required tools` (default on) + preferred overrides: magic secateurs, bottomless bucket, seed
dibber, spade. If the item exists in the bank it is withdrawn/equipped automatically.

### 3.8 Advanced (position 7)
`Run even if nothing is due` (prediction override) · action delays / tick jitter · antiban intensity ·
retry limits · per-category compost override · debug logging. Default experience never needs this.

---

## 4. Smart services (the "how")

Each service is a single-responsibility unit with a small interface, reusable by future activities.

| Service | Responsibility | Wraps / extends |
|---|---|---|
| **TravelService** | `travelTo(WorldPoint)` using the best available transport; honour Travel settings (min energy, stamina, restore, avoid-expensive) | thin wrapper over `Rs2Walker` (already transport-aware) + `Rs2Player` |
| **RunPlanner** | Simulate the whole enabled run's item needs BEFORE banking; validate ≤28 slots; **split into multiple bank trips** when a single prep exceeds capacity; account for equipped tools (0 slots), bottomless bucket, noted payments; never emit an impossible withdrawal | extends existing `InventoryPlan`/`InventoryPlanner` |
| **BankService** | Deposit-all-except, withdraw the plan, verify, resolve reservations (dose/charge/variant), toggle noted/unnoted | exists — extend for equipment |
| **EquipmentService** | Auto-detect required tools from `Rs2Inventory`/`Rs2Equipment`; withdraw-if-in-bank; equip what should be worn. **Must cover special farming items (magic secateurs — +10% herb yield) AND teleport equipables: farming cape (unlimited patch teleports), explorer's ring, rings/amulets/capes/jewellery, teleport tabs.** Prefer worn variants; recognise items already worn (don't duplicate). | new; `Rs2Bank.withdrawAndEquip` (name-based, wears; NOT per-id collection scan — that was slow & left pieces unworn), `Rs2Equipment` |
| **PaymentService** | Resolve each crop's protection payment item + amount; drive the pay-gardener dialogue | new; folds current PatchInteractor payment logic |
| **PatchPredictor** | "Is this run due?" via FarmingWorld growth prediction | = `FarmDue` (built) |
| **PatchInteractor** | Per-patch FSM: rake/clear/compost/plant/harvest with confirmed transitions | exists |

**Task model unchanged:** the scheduler still runs `FarmingTask`s in order, banking between them; each task
delegates travel to TravelService, prep to RunPlanner+BankService+EquipmentService, patches to
PatchInteractor. The config layer feeds each task; the services do the work.

### 4.1 RunPlanner multi-trip split (the main new engineering)
`InventoryPlan` already counts slots correctly (equipped=0, stackable/noted=1, loose=qty, reservations=1).
RunPlanner adds: given a task's full requirement, if `slotCount() > 28`, partition the withdrawals into
ordered trips (essential tools/teleports every trip; saplings/seeds/payments split by patch batches),
banking between batches. If even one indivisible unit can't fit, report precisely rather than loop. A
self-check asserts: a plan of N loose items splits into `ceil(N/free)` feasible trips.

---

### 4.2 Research-informed additions (approved)
- **Presets** — see §3.1. Native (enum + a config listener that sets component toggles).
- **"Next due" overlay** — the overlay shows per-activity ready/countdown state from `FarmDue` prediction
  (+ a ~50-min birdhouse timer), e.g. "Trees: ready · Herbs: 41m". Tells the user *when* to run.
- **Scheduled auto-run** — `IrkedFarmerPlugin implements SchedulablePlugin`: `getStartCondition()` fires
  when any enabled activity is due (via `FarmDue`/timers), `getStopCondition()` when the run finishes, so
  the plugin wakes and runs itself between other activities. Farming is inherently timer-based; this is the
  biggest automation win.
- **Special items** — TravelService/EquipmentService must treat the **farming cape** as a primary patch
  teleport, plus explorer's ring / rings / capes / jewellery / teleport tabs, and **magic secateurs** as a
  yield boost. Never hardcode a single teleport per patch — let TravelService pick from what the player owns.

## 5. Intelligent defaults

New user path: open plugin → **Run Builder**: tick the crops → (optional) pick seeds → start near a bank.
Everything else (tools, travel, banking, compost application, payments, teleport selection, prediction)
resolves automatically from bank contents + Microbot APIs.

---

## 6. Migration

The current config is replaced wholesale: keys are renamed/merged into the sections above. No released
version exists, so no user settings are preserved. The plugin config group stays `irkedfarmer`.

---

## 7. Microbot integration (explicit)

`Rs2Bank` (banking/inventory/equip withdrawal) · `Rs2Inventory`/`Rs2Equipment` (state + tool detection) ·
`Rs2Walker`/WebWalker (routing/travel) · Queryable caches (NPC/TileObject/TileItem/Player — patch & NPC
discovery, fewer repeated queries) · existing teleport/transport utilities (no custom routing) ·
`Rs2Player`/animation state (act only when appropriate) · `Rs2Antiban` (delays, camera, hover, human
decisions). Scheduled, state-driven execution; no client-thread blocking; client-thread access only where
required (widgets/varbits/prediction); no unnecessary polling.

---

## 8. Verification (backend must actually work)

- **Build green** after each phase (`./gradlew build -PpluginList=IrkedFarmerPlugin`).
- **Self-checks (no client):** RunPlanner slot-count + multi-trip split; PaymentService payment resolution
  per crop; compost-application decision table (tree+protect→no compost, herb→compost).
- **Live test (with client):** each component solo end-to-end (bank→travel→patch cycle→bank), then a
  multi-component run; verify conditional reveal in the config panel; verify no overflow and correct route
  via Rs2Walker (TGV/instanced patches watched).
- **Config comprehension check:** a fresh user can build a herb+tree run touching only Run Builder + seeds.

---

## 9. Risks

| Risk | Mitigation |
|---|---|
| No true conditional-reveal exists (see §3 correction) — a disabled component's section is still visible, just collapsed | Accepted: matches every existing Hub plugin's config UX; revisit only if RuneLite adds the capability |
| Whole-run simulation regresses the working per-task banking | RunPlanner wraps existing InventoryPlanner; keep per-task ≤28 path as the common case, split only when needed |
| Delegating routing to Rs2Walker leaves a patch unreachable (TGV/instanced) | Already handled by walker transports (Elkoy/rowboat); verify live, fix specific patch if it fails |
| Losing capability in the consolidation | Map every current setting to its new home before deleting (§3 covers all) |
