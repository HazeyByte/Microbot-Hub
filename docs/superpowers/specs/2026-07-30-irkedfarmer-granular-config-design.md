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

**CORRECTION (2026-07-30, during planning):** the original text below claimed `HerbPatch` already has all
four `Patch` fields/methods — verified false against the actual file. `HerbPatch` has no `configKey`
(regions are matched by a `switch` on `regionName` to distinct config methods like `enableArdougne()`, not
a generic keyName) and no `farmingLevel`/`hasRequiredLevel()` (herb level-gating happens via seed selection,
not per-region). Retrofitting it would mean inventing fields it doesn't need for anything in this
sub-project — `HerbPatch` already has region-level granularity (§4) and needs no interface to get it. Scope
correction: **only `FarmPatch` implements `Patch`** in this sub-project. `HerbPatch` is untouched.

Add a minimal `Patch` interface capturing only what's genuinely common across the categories that need it:

```java
public interface Patch {
    WorldPoint getLocation();
    String getConfigKey();
    int getFarmingLevel();
    boolean hasRequiredLevel();
}
```

`FarmPatch` (trees/fruit/hardwood) already has all four as fields/methods today — this is
`implements Patch`, not a rewrite. Category-specific fields (`objectId`, `leprechaunId`, `kind`) stay
exactly where they are. No unified schema with farmer-services/tools/teleports/banking-strategy as
interface methods — those differ enough per category (and are out of scope per §6) that forcing them into
one interface now would mean speculative no-op methods on categories that don't need them yet.

`Patch` exists so config-toggle wiring and enabled-patch filtering can be written once against the
interface instead of duplicated per task.

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

**CORRECTION (2026-07-30, during planning):** two gaps found verifying this section against the actual
task code. (a) `HardwoodRunTask.isDue()` currently hardcodes `true` with the comment "no FarmingWorld tab
tracks hardwood (Fossil/Avium); always attempt" — verified accurate, `PatchImplementation` has no hardwood
entries at the Fossil Island/Avium Savannah locations `FarmPatch` uses. Hardwood gets **no** per-patch
GROWING-skip; it keeps today's always-attempt behaviour. (b) `Rs2Farming.predictPatchState` takes a
QuestHelper `FarmingPatch`, not our `FarmPatch` — the two aren't the same type and nothing maps one to the
other today. That mapping has to be built, not assumed.

- `FarmDue.anyReady(...)` — the current per-task boolean "is this run worth doing" gate — is reimplemented
  on top of `Rs2Farming.getPatchesByTab` / `predictPatchState`, dropping the direct
  `FarmingWorld`/`FarmingHandler`/client-thread wiring that duplicates what `Rs2Farming` already does.
  Hardwood tasks don't call this (see correction above) and are unaffected.
- New: a `FarmPatch` → QuestHelper `FarmingPatch` lookup matches by nearest location within a 10-tile
  tolerance, scoped to the same `Tab`/`PatchImplementation` pair (`Tab.TREE`+`PatchImplementation.TREE` for
  regular trees, `Tab.TREE`+`PatchImplementation.FRUIT_TREE` for fruit trees) — tolerance instead of exact
  `WorldPoint` equality because `FarmPatch`'s hardcoded coordinates and the patch's tracked anchor tile
  aren't guaranteed to be the identical tile (see `docs/PLUGIN_DEBUGGING_NOTES.md` §7's documented
  hardcoded-coordinate-drift pattern — same tolerance-based approach used there). Regional patches of the
  same kind are hundreds of tiles apart, so a 10-tile tolerance can't cross-match the wrong patch.
- `TreeRunTask`/`FruitTreeRunTask`'s `enabledPatches()` additionally drops individual patches whose matched
  `FarmingPatch` predicts `CropState.GROWING`, so the walker never routes to a patch known to still be
  growing. `HardwoodRunTask`'s `enabledPatches()` is unchanged (level filter + new per-patch config toggle
  only, per §4).
- Patches with no match found, or a match with no tracked prediction (`null` — never visited, or
  Timetracking hasn't observed them yet), are **kept in the route**, matching `FarmDue`'s existing "can't
  predict — attempt rather than silently skip" behaviour. This sub-project must not regress a never-visited
  patch into being permanently skipped.
- `HerbPatch`-based tasks (`HerbRunTask`) already do their own per-location due-checking via
  `FarmingHandler.predictPatch` directly; out of scope for this rework (§3 correction — `HerbPatch` isn't
  touched in this sub-project).

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
- **Self-check (no client):** every existing `FarmPatch` entry has a corresponding `@ConfigItem` and
  resolves through the new `Patch`-typed enabled-patch filter; toggling a patch off removes it from
  `enabledPatches()` without affecting others. `HerbPatch` is unchanged and out of scope.
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
