# irkedFarmer — Granular Patch Config + Timetracking-Aware Skipping (Sub-project 1)

**Date:** 2026-07-30
**Author:** irkedMATT
**Status:** Design — pending spec review
**Supersedes:** nothing (additive to the config shipped in the 2026-07-26 redesign)

---

## 1. Goal

Replace the coarse "one toggle per activity" Run Builder (Tree run / Fruit tree run / Hardwood run / Herb
run / Birdhouse run) with **per-patch enable/disable**, so a user can build routes like "Farming Guild
only" or "every tree patch except Prifddinas" instead of all-or-nothing per activity.

This is **sub-project 1 of a larger AIO farming redesign** (full spec from Matt covers per-category harvest
behaviour, XP/Profit/Ironman presets, requirement validation, dynamic inventory planning, route
optimisation, farmer NPC service integration, and brand-new patch types). Those are explicitly deferred to
their own specs — see §6. This sub-project only builds the foundation they'll sit on: real per-patch
identity in the config and in the route.

Two acceptance criteria:
1. Every patch the plugin already handles (tree, fruit tree, hardwood, herb, flower, allotment) gets an
   individual config checkbox, following the pattern the herb section already uses for its 9 locations.
2. A patch the Timetracking data says is still growing is skipped **before** a wasted walk there, not
   discovered on arrival (today's `PatchInteractor.Outcome.GROWING` fix only cuts the wasted *time on
   arrival*, not the wasted *walk*).

---

## 2. Research findings (corrects an earlier wrong assumption)

Before writing this spec, three things were verified against the actual client source rather than assumed:

1. **`Rs2Farming` already exists** (`runelite-client/.../util/farming/Rs2Farming.java`) — a Microbot utility
   wrapping `FarmingWorld`/`FarmingHandler` (from
   `microbot.questhelper.helpers.mischelpers.farmruns`) behind `getPatchesByTab`, `predictPatchState`,
   `getReadyPatches`, `isFarmingSystemReady`, etc. It already does the client-thread + injector wiring that
   `irkedfarmer.service.FarmDue` currently hand-rolls a second time.
2. **That QuestHelper-side `FarmingHandler` is not a divergent reimplementation of RuneLite's Timetracking
   tracker** — it imports `net.runelite.client.plugins.timetracking.TimeTrackingConfig` directly and reads
   `patch.configKey()` from the *same* persisted `timetracking.<profile>.<key> = varbit:timestamp` config
   values that `timetracking.farming.FarmingTracker.updateData()` writes on every game tick (verified in
   both source files). So `FarmDue`/`Rs2Farming` predictions are already backed by the real Timetracking
   data — there is no bridging or sync gap to build. The corrected scope of "sync with Timetracking" is:
   route `FarmDue` through the existing `Rs2Farming` wrapper (reuse) and act on the prediction per-patch,
   not just as one whole-run due/not-due gate (the actual behavioural gap).
3. **`PatchImplementation`** (same QuestHelper package) already enumerates categories the parent spec asks
   for as "future": `MUSHROOM`, `HESPORI`, `BUSH`, `HOPS`, `CACTUS`, `BELLADONNA`, `SEAWEED`, `CELASTRUS`,
   `CALQUAT`, `REDWOOD`, `SPIRIT_TREE`, `GRAPES`, with regions/varbits already mapped. It has no game object
   ID (Timetracking only needs varbits, not the clickable object), so it can't replace `FarmPatch`/
   `HerbPatch` outright — but it means a later "add new patch type" sub-project starts from known
   locations/varbits instead of wiki research from zero. Noted for the roadmap, not used in this
   sub-project.

---

## 3. Data model

Add a minimal `Patch` interface capturing only what's genuinely common across every category:

```java
public interface Patch {
    WorldPoint getLocation();
    String getConfigKey();
    int getFarmingLevel();
    boolean hasRequiredLevel();
}
```

`FarmPatch` (trees/fruit/hardwood) and `HerbPatch` already have all four as fields/methods today — this is
a `implements Patch` on each, not a rewrite. Category-specific fields (`objectId`, `leprechaunId`, `kind` on
`FarmPatch`; herb's varbit-driven fields on `HerbPatch`) stay exactly where they are. No unified schema with
farmer-services/tools/teleports/banking-strategy as interface methods — those differ enough per category
(and are out of scope per §6) that forcing them into one interface now would mean speculative no-op methods
on categories that don't need them yet. Add them to the interface when a sub-project that actually
implements that behaviour needs it.

`Patch` exists so config-toggle wiring, enabled-patch filtering, and (later) route ordering can be written
once against the interface instead of once per concrete enum.

---

## 4. Config layout

One `@ConfigItem` boolean per patch, added to each category's existing `@ConfigSection` (`treesSection`,
`fruitSection`, `hardwoodSection`). This matches the herb section's current `enableArdougne` /
`enableCatherby` / ... convention exactly — no new UI mechanism, fully native RuneLite config panel,
discoverable without docs.

Herb/flower/allotment are already at the target per-location granularity today (9 region toggles:
`enableArdougne`...`enableVarlamore`) — a user can already do "Farming Guild only." What they *can't* do
yet is enable flowers at one region and not another, since `herbEnableFlowers`/`herbEnableAllotments` are
global switches applied across every enabled region. That per-region-per-crop split is a real gap but a
small, isolated one — left for a follow-up pass rather than bundled into this sub-project's tree/fruit/
hardwood work, since it only touches `HerbRunTask` and doesn't block anything else in §1-§5.

The Run Builder's activity checkboxes (`treeRun`, `fruitTreeRun`, etc.) are kept as-is — they still gate
whether that category's `FarmingTask` runs at all. What changes is that `enabledPatches()` in each task
(`TreeRunTask`, `FruitTreeRunTask`, `HardwoodRunTask`) filters by the new per-patch toggle in addition to
the existing farming-level check, instead of unconditionally returning every level-eligible patch of that
kind.

Adding a new patch later is mechanical: one enum entry + one `@ConfigItem` + one line in that category's
enabled-patch lookup.

---

## 5. FarmDue / route-skipping rework

- `FarmDue.anyReady(...)` — the current per-task boolean "is this run worth doing" gate — is reimplemented
  on top of `Rs2Farming.getPatchesByTab` / `predictPatchState`, dropping the direct
  `FarmingWorld`/`FarmingHandler`/client-thread wiring that duplicates what `Rs2Farming` already does.
- New: each task's `enabledPatches()` additionally drops individual patches `Rs2Farming.predictPatchState`
  reports as `CropState.GROWING`, so the walker never routes to a patch known to still be growing.
- Patches with no tracked prediction (`null` — never visited, or Timetracking hasn't observed them yet) are
  **kept in the route**, matching `FarmDue`'s existing "can't predict — attempt rather than silently skip"
  behaviour. This sub-project must not regress a never-visited patch into being permanently skipped.
- `HerbPatch`-based tasks (`HerbRunTask`) already do their own per-location due-checking; if it duplicates
  logic that now lives in the shared helper, consolidate during implementation — no behavioural change
  intended there beyond removing duplication.

---

## 6. Out of scope (future sub-projects, each gets its own spec)

- Per-category harvest/post-harvest behaviour matrix (chop vs. pay-to-remove vs. leave; note vs. bank vs.
  drop produce).
- XP-focus / Profit-focus / Ironman-focus presets.
- Pre-run requirement validation pass (quest/diary/tool/rune/charge checks with user-facing reasons).
- Dynamic inventory/equipment planning driven by which actions are enabled, and mid-run overflow handling
  (bank/note/drop-when-full).
- Teleport/unlock-aware dynamic route optimisation.
- Farmer NPC service catalogue (wiki-researched, data-driven per patch).
- Brand-new patch types: bush, hops, cactus, belladonna, celastrus, redwood, hespori, seaweed, mushroom.
  (§2.3 gives the next sub-project a head start on locations/varbits via `PatchImplementation`.)

---

## 7. Verification

- **Build green**: `./gradlew build -PpluginList=IrkedFarmerPlugin`.
- **Self-check (no client):** every existing `FarmPatch`/`HerbPatch` entry has a corresponding
  `@ConfigItem` and resolves through the new `Patch`-typed enabled-patch filter; toggling a patch off
  removes it from `enabledPatches()` without affecting others.
- **Live test:** disable all but one tree patch, confirm the run only visits that patch; confirm a patch
  `Rs2Farming` predicts as `GROWING` (freshly planted, revisited before maturity) is skipped without a
  walk; confirm a never-visited patch (no tracked prediction) is still attempted.
- **Regression check:** existing herb per-location toggles and the Run Builder activity toggles keep
  working exactly as before — this sub-project is additive.

---

## 8. Risks

| Risk | Mitigation |
|---|---|
| `Rs2Farming` prediction requires the QuestHelper classes it depends on to be present/initialized; if `isFarmingSystemReady()` is false, per-patch skipping silently does nothing | Fall back to "attempt anyway" (same as today) rather than blocking — never a hard dependency |
| Config panel grows to ~35-40 checkboxes across categories | Matches the herb section's already-shipped convention; collapsible `@ConfigSection` per category (existing pattern) keeps it manageable |
| Adding `Patch` interface churns `FarmPatch`/`HerbPatch` call sites | Interface is additive (`implements Patch`); existing concrete-type call sites keep compiling unchanged |
