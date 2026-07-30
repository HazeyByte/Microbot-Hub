# irkedFarmer Granular Patch Config (Sub-project 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every tree/fruit-tree/hardwood patch its own config enable-toggle (matching the pattern the herb section already uses), and skip routing to a patch the Timetracking-backed prediction says is still growing.

**Architecture:** A minimal `Patch` interface on `FarmPatch` exposes a generic `isEnabled()` read straight from `ConfigManager` by string key (no reflection, no giant switch). `FarmDue` is rebuilt on the existing `Rs2Farming` client utility instead of hand-rolled `FarmingWorld`/client-thread wiring, and gains a tolerance-based location match from our hardcoded `FarmPatch` coordinates to QuestHelper's tracked `FarmingPatch` so per-patch `GROWING` predictions can gate the route.

**Tech Stack:** Java 11, RuneLite/Microbot plugin APIs (`ConfigManager`, `@ConfigItem`), existing `Rs2Farming` / QuestHelper farm-run classes.

## Global Constraints

- Spec: `docs/superpowers/specs/2026-07-30-irkedfarmer-granular-config-design.md` (as corrected 2026-07-30).
- `HerbPatch` is untouched — out of scope (spec §3 correction).
- `HardwoodRunTask` gets the per-patch enable filter only, no `GROWING`-skip — there is no `FarmingWorld`/Timetracking coverage for Fossil Island/Avium Savannah hardwood patches (spec §5 correction, verified against `HardwoodRunTask.isDue()`'s existing comment).
- No JUnit test harness exists for this package (verified: no file matches `src/test/**/*irkedfarmer*`). Verification per task is `./gradlew build -PpluginList=IrkedFarmerPlugin` (must show `BUILD SUCCESSFUL`, zero warnings introduced). **Do not use the live client/agent-server to test during this plan** — only when Matt explicitly asks for a live test in a later message.
- Bump `IrkedFarmerPlugin.version` from `"0.9.6"` to `"0.9.7"` as part of the final task (per this repo's CLAUDE.md: always increment on change).
- Every task's diff must compile clean before moving to the next task — do not stack changes across tasks.

---

### Task 1: `Patch` interface + `FarmPatch` implements it

**Files:**
- Create: `src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/model/Patch.java`
- Modify: `src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/model/FarmPatch.java:23`

**Interfaces:**
- Produces: `Patch` interface with `getLocation()`, `getConfigKey()`, `getFarmingLevel()`, `hasRequiredLevel()`, and a default `isEnabled()` — consumed by Tasks 4-6.
- Produces: `FarmPatch implements Patch` — `FarmPatch`'s existing Lombok-generated getters (`getLocation()`, `getConfigKey()`, `getFarmingLevel()`) and existing `hasRequiredLevel()` method already satisfy the interface; no new methods needed on `FarmPatch` itself.

- [ ] **Step 1: Create the `Patch` interface**

```java
package net.runelite.client.plugins.microbot.irkedfarmer.model;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;

/**
 * Common shape shared by patch categories that carry an individual config toggle. Only {@link FarmPatch}
 * implements this today — see
 * docs/superpowers/specs/2026-07-30-irkedfarmer-granular-config-design.md §3 for why {@code HerbPatch}
 * is excluded.
 */
public interface Patch {
    WorldPoint getLocation();
    String getConfigKey();
    int getFarmingLevel();
    boolean hasRequiredLevel();

    /**
     * Per-patch enable toggle, read directly from {@link ConfigManager} by string key rather than
     * through the {@link IrkedFarmerConfig} interface proxy — {@code ConfigManager.getConfiguration(group,
     * key, type)} reads the raw persisted value and returns {@code null} when unset; it does not fall
     * back to the {@code @ConfigItem}'s annotated default (verified against ConfigManager source). Unset
     * therefore means "never explicitly disabled" here, matching every per-patch {@code @ConfigItem}
     * declared with {@code default boolean x() { return true; }}.
     *
     * {@code Microbot} has no static {@code getConfigManager()} accessor (verified: its
     * {@code ConfigManager} field is private with no getter) — {@code Microbot.getInjector()
     * .getInstance(ConfigManager.class)} is the pattern already used elsewhere in the client for this
     * exact lookup (see {@code Rs2Camera.java}).
     */
    default boolean isEnabled() {
        Boolean value = Microbot.getInjector().getInstance(ConfigManager.class)
                .getConfiguration(IrkedFarmerConfig.GROUP, getConfigKey(), boolean.class);
        return value == null || value;
    }
}
```

- [ ] **Step 2: Make `FarmPatch` implement `Patch`**

In `FarmPatch.java`, change:
```java
public enum FarmPatch {
```
to:
```java
public enum FarmPatch implements Patch {
```

- [ ] **Step 3: Compile**

Run: `./gradlew build -PpluginList=IrkedFarmerPlugin`
Expected: `BUILD SUCCESSFUL`, no warnings.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/model/Patch.java src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/model/FarmPatch.java
git commit -m "feat(irkedfarmer): add Patch interface, FarmPatch implements it"
```

---

### Task 2: Per-patch `@ConfigItem` toggles for tree/fruit/hardwood

**Files:**
- Modify: `src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/IrkedFarmerConfig.java:170-174` (trees), `:186-190` (fruit), `:202-206` (hardwood)

**Interfaces:**
- Produces: 17 new `@ConfigItem boolean` methods, one per distinct `FarmPatch.configKey` (three `FOSSIL_TREE_*` entries share `"fossilHardwood"`, so hardwood needs only 2 checkboxes for 4 enum entries) — consumed by `Patch.isEnabled()` (Task 1) via the raw config-key string, not by name, so no other task needs to reference these methods directly.

- [ ] **Step 1: Add tree patch toggles**

In `IrkedFarmerConfig.java`, after the `protectTrees()` block (ends `IrkedFarmerConfig.java:174`) and before `// ---- Fruit trees ----`, insert:

```java

    @ConfigItem(keyName = "gnomeStrongholdTree", name = "Gnome Stronghold", description = "Gnome Stronghold tree patch",
            section = treesSection, position = 2)
    default boolean gnomeStrongholdTree() { return true; }

    @ConfigItem(keyName = "farmingGuildTree", name = "Farming Guild", description = "Farming Guild tree patch",
            section = treesSection, position = 3)
    default boolean farmingGuildTree() { return true; }

    @ConfigItem(keyName = "taverleyTree", name = "Taverley", description = "Taverley tree patch",
            section = treesSection, position = 4)
    default boolean taverleyTree() { return true; }

    @ConfigItem(keyName = "faladorTree", name = "Falador", description = "Falador tree patch",
            section = treesSection, position = 5)
    default boolean faladorTree() { return true; }

    @ConfigItem(keyName = "lumbridgeTree", name = "Lumbridge", description = "Lumbridge tree patch",
            section = treesSection, position = 6)
    default boolean lumbridgeTree() { return true; }

    @ConfigItem(keyName = "varrockTree", name = "Varrock", description = "Varrock tree patch",
            section = treesSection, position = 7)
    default boolean varrockTree() { return true; }

    @ConfigItem(keyName = "auburnvaleTree", name = "Auburnvale", description = "Auburnvale tree patch",
            section = treesSection, position = 8)
    default boolean auburnvaleTree() { return true; }

    @ConfigItem(keyName = "prifddinasCrystal", name = "Prifddinas (Crystal)", description = "Prifddinas crystal tree patch (requires level 74)",
            section = treesSection, position = 9)
    default boolean prifddinasCrystal() { return true; }
```

- [ ] **Step 2: Add fruit tree patch toggles**

After the `protectFruitTrees()` block (ends `IrkedFarmerConfig.java:190` before this edit) and before `// ---- Hardwood ----`, insert:

```java

    @ConfigItem(keyName = "gnomeStrongholdFruit", name = "Gnome Stronghold", description = "Gnome Stronghold fruit tree patch",
            section = fruitSection, position = 2)
    default boolean gnomeStrongholdFruit() { return true; }

    @ConfigItem(keyName = "treeGnomeVillageFruit", name = "Tree Gnome Village", description = "Tree Gnome Village fruit tree patch",
            section = fruitSection, position = 3)
    default boolean treeGnomeVillageFruit() { return true; }

    @ConfigItem(keyName = "farmingGuildFruit", name = "Farming Guild", description = "Farming Guild fruit tree patch (requires level 85)",
            section = fruitSection, position = 4)
    default boolean farmingGuildFruit() { return true; }

    @ConfigItem(keyName = "brimhavenFruit", name = "Brimhaven", description = "Brimhaven fruit tree patch",
            section = fruitSection, position = 5)
    default boolean brimhavenFruit() { return true; }

    @ConfigItem(keyName = "catherbyFruit", name = "Catherby", description = "Catherby fruit tree patch",
            section = fruitSection, position = 6)
    default boolean catherbyFruit() { return true; }

    @ConfigItem(keyName = "lletyaFruit", name = "Lletya", description = "Lletya fruit tree patch",
            section = fruitSection, position = 7)
    default boolean lletyaFruit() { return true; }

    @ConfigItem(keyName = "kastoriFruit", name = "Kastori (Varlamore)", description = "Kastori fruit tree patch",
            section = fruitSection, position = 8)
    default boolean kastoriFruit() { return true; }
```

- [ ] **Step 3: Add hardwood patch toggles**

After the `protectHardwood()` block (ends `IrkedFarmerConfig.java:206` before this edit) and before `// ---- Herbs ----`, insert:

```java

    @ConfigItem(keyName = "fossilHardwood", name = "Fossil Island", description = "Fossil Island hardwood patches",
            section = hardwoodSection, position = 2)
    default boolean fossilHardwood() { return true; }

    @ConfigItem(keyName = "aviumHardwood", name = "Avium Savannah", description = "Avium Savannah hardwood patch",
            section = hardwoodSection, position = 3)
    default boolean aviumHardwood() { return true; }
```

- [ ] **Step 4: Compile**

Run: `./gradlew build -PpluginList=IrkedFarmerPlugin`
Expected: `BUILD SUCCESSFUL`, no warnings.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/IrkedFarmerConfig.java
git commit -m "feat(irkedfarmer): per-patch config toggles for tree/fruit/hardwood"
```

---

### Task 3: Rework `FarmDue` on `Rs2Farming`, add tolerance-based prediction lookup

**Files:**
- Modify: `src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/service/FarmDue.java` (full rewrite, file is 37 lines today)

**Interfaces:**
- Consumes: `Rs2Farming.getPatchesByTab(Tab)`, `Rs2Farming.batchPredictAll(List<FarmingPatch>)`, `Rs2Farming.predictPatchState(FarmingPatch)` (all `public static`, already exist in `net.runelite.client.plugins.microbot.util.farming.Rs2Farming`).
- Produces: `FarmDue.anyReady(Tab tab)` (replaces the old 4-arg signature) — consumed by Task 4/5. `FarmDue.predictNearest(WorldPoint location, Tab tab, PatchImplementation implementation)` returning `CropState` or `null` — consumed by Task 4/5.

- [ ] **Step 1: Replace the full file contents**

```java
package net.runelite.client.plugins.microbot.irkedfarmer.service;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.CropState;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.FarmingPatch;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.PatchImplementation;
import net.runelite.client.plugins.microbot.util.farming.Rs2Farming;
import net.runelite.client.plugins.timetracking.Tab;

import java.util.List;
import java.util.Map;

/**
 * "Is this run worth doing?" via FarmingWorld growth prediction, through the existing {@link Rs2Farming}
 * wrapper rather than hand-rolling FarmingWorld/FarmingHandler/client-thread wiring a second time. A run
 * is due if ANY patch of the given tab is not still GROWING (ready to check/replant, empty, diseased or
 * dead) — a run where every patch is still growing is skipped instead of wasting a bank + walk trip.
 */
public final class FarmDue {

    private FarmDue() {}

    /**
     * Nearest-match tolerance in tiles between a {@code FarmPatch}'s hardcoded location and the
     * QuestHelper {@link FarmingPatch}'s tracked anchor tile. See docs/PLUGIN_DEBUGGING_NOTES.md §7:
     * hardcoded-coordinate drift is handled with a tolerance elsewhere in this codebase, not exact
     * equality. Regional patches of the same kind are hundreds of tiles apart, so this can't cross-match
     * the wrong patch.
     */
    private static final int MATCH_TOLERANCE = 10;

    public static boolean anyReady(Tab tab) {
        List<FarmingPatch> patches = Rs2Farming.getPatchesByTab(tab);
        if (patches.isEmpty()) {
            return true; // can't predict — attempt rather than silently skip
        }
        Map<FarmingPatch, CropState> states = Rs2Farming.batchPredictAll(patches);
        for (FarmingPatch patch : patches) {
            if (states.get(patch) != CropState.GROWING) {
                return true;
            }
        }
        return false;
    }

    /**
     * Predicted state of whichever tracked {@link FarmingPatch} is nearest {@code location} (within
     * {@link #MATCH_TOLERANCE} tiles) among patches of the given tab/implementation, or {@code null} if
     * none is tracked yet (never visited, or Timetracking hasn't observed it). Callers must treat
     * {@code null} as "attempt anyway", not "skip".
     */
    public static CropState predictNearest(WorldPoint location, Tab tab, PatchImplementation implementation) {
        FarmingPatch nearest = null;
        int bestDistance = MATCH_TOLERANCE + 1;
        for (FarmingPatch patch : Rs2Farming.getPatchesByTab(tab)) {
            if (patch.getImplementation() != implementation) {
                continue;
            }
            int distance = location.distanceTo(patch.getLocation());
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = patch;
            }
        }
        return nearest == null ? null : Rs2Farming.predictPatchState(nearest);
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew build -PpluginList=IrkedFarmerPlugin`
Expected: this step **fails** with errors in `TreeRunTask.java`/`FruitTreeRunTask.java` (old 4-arg `FarmDue.anyReady(...)` call no longer matches) — that's expected, Task 4/5 fix the call sites. Confirm the *only* errors are those two call sites (no other unexpected breakage) before continuing.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/service/FarmDue.java
git commit -m "refactor(irkedfarmer): rebuild FarmDue on Rs2Farming, add tolerance-based per-patch prediction"
```

Note: this commit intentionally leaves the build red until Task 4/5 land — that's the smallest reviewable unit for this specific change (the whole `FarmDue` rewrite is one coherent diff). Task 4 is the very next task.

---

### Task 4: `TreeRunTask` — per-patch enable filter + GROWING-skip

**Files:**
- Modify: `src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/task/TreeRunTask.java` (full file, 58 lines today)

**Interfaces:**
- Consumes: `Patch.isEnabled()` (Task 1), `FarmDue.anyReady(Tab)` and `FarmDue.predictNearest(WorldPoint, Tab, PatchImplementation)` (Task 3).
- Produces: `TreeRunTask(IrkedFarmerConfig cfg)` — constructor signature changes from 4 args to 1 (drops now-unused `FarmingWorld`/`ClientThread`/`ConfigManager` fields, since `FarmDue.anyReady` no longer needs them). Consumed by Task 6 (`IrkedFarmerScript.buildTasks()`).

- [ ] **Step 1: Replace the full file contents**

```java
package net.runelite.client.plugins.microbot.irkedfarmer.task;

import lombok.RequiredArgsConstructor;
import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;
import net.runelite.client.plugins.microbot.irkedfarmer.model.FarmPatch;
import net.runelite.client.plugins.microbot.irkedfarmer.model.TreeKind;
import net.runelite.client.plugins.microbot.irkedfarmer.service.FarmDue;
import net.runelite.client.plugins.microbot.irkedfarmer.service.InventoryPlanner;
import net.runelite.client.plugins.microbot.irkedfarmer.service.PatchRunner;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.CropState;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.PatchImplementation;
import net.runelite.client.plugins.timetracking.Tab;

import java.util.List;
import java.util.stream.Collectors;

/** Regular tree patches + Prifddinas crystal tree. */
@RequiredArgsConstructor
public class TreeRunTask implements FarmingTask {
    private final IrkedFarmerConfig cfg;

    @Override
    public String name() {
        return "Tree run";
    }

    @Override
    public boolean isEnabled(IrkedFarmerConfig cfg) {
        return cfg.treeRun();
    }

    @Override
    public boolean isDue() {
        return cfg.runWhenNothingDue() || FarmDue.anyReady(Tab.TREE);
    }

    @Override
    public InventoryPlan plan() {
        return InventoryPlanner.planRegularTrees(cfg.selectedTree(), enabledPatches(),
                cfg.protectTrees(), cfg.compostType(), cfg.useEnergyPotion());
    }

    @Override
    public TaskResult execute() {
        return PatchRunner.run(name(), cfg, plan(), enabledPatches());
    }

    private List<FarmPatch> enabledPatches() {
        return FarmPatch.ofKind(TreeKind.TREE).stream()
                .filter(FarmPatch::hasRequiredLevel)
                .filter(FarmPatch::isEnabled)
                .filter(p -> FarmDue.predictNearest(p.getLocation(), Tab.TREE, PatchImplementation.TREE) != CropState.GROWING)
                .collect(Collectors.toList());
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew build -PpluginList=IrkedFarmerPlugin`
Expected: still fails — `FruitTreeRunTask.java` (Task 5) and `IrkedFarmerScript.java` (Task 6) still reference the old constructor/signature. Confirm the *only* remaining errors are in those two files.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/task/TreeRunTask.java
git commit -m "feat(irkedfarmer): TreeRunTask per-patch enable filter + GROWING-skip"
```

---

### Task 5: `FruitTreeRunTask` — per-patch enable filter + GROWING-skip

**Files:**
- Modify: `src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/task/FruitTreeRunTask.java` (full file, 57 lines today)

**Interfaces:**
- Consumes: same as Task 4, with `Tab.FRUIT_TREE`/`PatchImplementation.FRUIT_TREE`.
- Produces: `FruitTreeRunTask(IrkedFarmerConfig cfg)` — same constructor simplification as Task 4. Consumed by Task 6.

- [ ] **Step 1: Replace the full file contents**

```java
package net.runelite.client.plugins.microbot.irkedfarmer.task;

import lombok.RequiredArgsConstructor;
import net.runelite.client.plugins.microbot.irkedfarmer.IrkedFarmerConfig;
import net.runelite.client.plugins.microbot.irkedfarmer.model.FarmPatch;
import net.runelite.client.plugins.microbot.irkedfarmer.model.TreeKind;
import net.runelite.client.plugins.microbot.irkedfarmer.service.FarmDue;
import net.runelite.client.plugins.microbot.irkedfarmer.service.InventoryPlanner;
import net.runelite.client.plugins.microbot.irkedfarmer.service.PatchRunner;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.CropState;
import net.runelite.client.plugins.microbot.questhelper.helpers.mischelpers.farmruns.PatchImplementation;
import net.runelite.client.plugins.timetracking.Tab;

import java.util.List;
import java.util.stream.Collectors;

/** Fruit tree patches. */
@RequiredArgsConstructor
public class FruitTreeRunTask implements FarmingTask {
    private final IrkedFarmerConfig cfg;

    @Override
    public String name() {
        return "Fruit tree run";
    }

    @Override
    public boolean isEnabled(IrkedFarmerConfig cfg) {
        return cfg.fruitTreeRun();
    }

    @Override
    public boolean isDue() {
        return cfg.runWhenNothingDue() || FarmDue.anyReady(Tab.FRUIT_TREE);
    }

    @Override
    public InventoryPlan plan() {
        return InventoryPlanner.planFruitTrees(cfg.selectedFruitTree(), enabledPatches(),
                cfg.protectFruitTrees(), cfg.compostType(), cfg.useEnergyPotion());
    }

    @Override
    public TaskResult execute() {
        return PatchRunner.run(name(), cfg, plan(), enabledPatches());
    }

    private List<FarmPatch> enabledPatches() {
        return FarmPatch.ofKind(TreeKind.FRUIT_TREE).stream()
                .filter(FarmPatch::hasRequiredLevel)
                .filter(FarmPatch::isEnabled)
                .filter(p -> FarmDue.predictNearest(p.getLocation(), Tab.FRUIT_TREE, PatchImplementation.FRUIT_TREE) != CropState.GROWING)
                .collect(Collectors.toList());
    }
}
```

- [ ] **Step 2: Compile**

Run: `./gradlew build -PpluginList=IrkedFarmerPlugin`
Expected: still fails — only `IrkedFarmerScript.java` (Task 6) still calls the old 4-arg constructors for `TreeRunTask`/`FruitTreeRunTask`. Confirm that's the only remaining error.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/task/FruitTreeRunTask.java
git commit -m "feat(irkedfarmer): FruitTreeRunTask per-patch enable filter + GROWING-skip"
```

---

### Task 6: `HardwoodRunTask` enable filter, wire up `IrkedFarmerScript`, version bump

**Files:**
- Modify: `src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/task/HardwoodRunTask.java:44-48`
- Modify: `src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/IrkedFarmerScript.java:73-81`
- Modify: `src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/IrkedFarmerPlugin.java:36`

**Interfaces:**
- Consumes: `Patch.isEnabled()` (Task 1), `TreeRunTask(IrkedFarmerConfig)`/`FruitTreeRunTask(IrkedFarmerConfig)` 1-arg constructors (Tasks 4-5).
- Produces: fully wired, compiling plugin — terminal task of this plan.

- [ ] **Step 1: Add the enable filter to `HardwoodRunTask`**

In `HardwoodRunTask.java`, change:
```java
    private List<FarmPatch> enabledPatches() {
        return FarmPatch.ofKind(TreeKind.HARD_TREE).stream()
                .filter(FarmPatch::hasRequiredLevel)
                .collect(Collectors.toList());
    }
```
to:
```java
    private List<FarmPatch> enabledPatches() {
        return FarmPatch.ofKind(TreeKind.HARD_TREE).stream()
                .filter(FarmPatch::hasRequiredLevel)
                .filter(FarmPatch::isEnabled)
                .collect(Collectors.toList());
    }
```

- [ ] **Step 2: Fix `IrkedFarmerScript.buildTasks()` call sites**

In `IrkedFarmerScript.java`, change:
```java
    private List<FarmingTask> buildTasks(IrkedFarmerConfig config) {
        return new ArrayList<>(Arrays.asList(
                new TreeRunTask(config, farmingWorld, clientThread, configManager),
                new FruitTreeRunTask(config, farmingWorld, clientThread, configManager),
                new HardwoodRunTask(config),
                new HerbRunTask(config, farmingWorld, clientThread, configManager),
                new BirdhouseRunTask(config)
        ));
    }
```
to:
```java
    private List<FarmingTask> buildTasks(IrkedFarmerConfig config) {
        return new ArrayList<>(Arrays.asList(
                new TreeRunTask(config),
                new FruitTreeRunTask(config),
                new HardwoodRunTask(config),
                new HerbRunTask(config, farmingWorld, clientThread, configManager),
                new BirdhouseRunTask(config)
        ));
    }
```

`farmingWorld`/`clientThread`/`configManager` `@Inject` fields stay on `IrkedFarmerScript` — `HerbRunTask` still needs them (out of scope, spec §3/§5 correction).

- [ ] **Step 3: Bump the plugin version**

In `IrkedFarmerPlugin.java`, change:
```java
    public static final String version = "0.9.6";
```
to:
```java
    public static final String version = "0.9.7";
```

- [ ] **Step 4: Compile**

Run: `./gradlew build -PpluginList=IrkedFarmerPlugin`
Expected: `BUILD SUCCESSFUL`, zero errors, zero new warnings. This is the first fully-green build since Task 2.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/task/HardwoodRunTask.java src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/IrkedFarmerScript.java src/main/java/net/runelite/client/plugins/microbot/irkedfarmer/IrkedFarmerPlugin.java
git commit -m "feat(irkedfarmer): wire per-patch config end-to-end, drop dead FarmDue params, bump to 0.9.7"
```

---

## Post-plan (not part of this plan's tasks)

Live verification (disable all but one tree patch and confirm the run only visits it; confirm a freshly-planted patch is skipped without a walk; confirm a never-visited patch is still attempted) is deferred until Matt asks for it directly — do not start the plugin or touch the agent server as part of executing this plan.
