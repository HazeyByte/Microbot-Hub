# MICROBOT SYSTEM CONSTITUTION
**Version 2.0 — Authoritative Design Document**

This is the authoritative design document for the Microbot ecosystem.
All AI-assisted development must treat this as the highest-level source of truth.
If any code, plugin behavior, or documentation conflicts with this file:
→ **This document defines the intended system design.**

---

## 1. SYSTEM OVERVIEW

Microbot is a RuneLite-based automation framework consisting of two primary layers:

### 1.1 Microbot (Core Client)

The core client is the foundation of the entire ecosystem. It wraps and extends the RuneLite client to provide a stable, safe automation surface.

**Core responsibilities:**
- Provide high-level game APIs (interaction, queries, player state, world state)
- Enforce ClientThread safety across all game interactions
- Provide the Queryable API system for filtering and selecting game entities
- Manage plugin lifecycle execution (load, run, stop, recover)
- Abstract away low-level RuneLite internals from script/plugin authors
- Expose tick-aware utilities (timers, delays, state helpers)

**Design constraints:**
- Core must remain stable across RuneLite updates
- Core APIs must be deterministic and side-effect-free where possible
- Core must never expose mutable game state to plugins directly

### 1.2 Microbot-Hub (Plugin and Script Layer)

The Hub is the extension layer where all user-facing automation logic lives.

**Hub responsibilities:**
- Implement plugins and scripts on top of core APIs
- Extend core APIs safely through composition, not modification
- Provide the plugin marketplace/distribution surface
- Never modify core engine behavior or RuneLite internals

**Design constraints:**
- Hub code is inherently volatile — scripts are disposable and replaceable
- Hub plugins must never assume core internals; only consume public APIs
- Hub is the correct place for game-specific logic (e.g., "bank at Edgeville")
- Stable, reusable systems that emerge from Hub work should be promoted to Core

---

## 2. EXECUTION MODEL

Microbot's execution model is tick-based, mirroring the game engine itself.

### 2.1 Tick Cycle

The game server processes state at approximately 600ms intervals (one "tick"). All automation must be designed around this rhythm.

```
Game Tick
  └─ onLoop() fires
       ├─ Evaluate current state
       ├─ Query required entities
       ├─ Execute one action
       └─ Return (wait for next tick)
```

### 2.2 State Machine Requirement

All scripts MUST be implemented as state machines. This is not optional.

**Why:** A state machine forces the developer to reason explicitly about what the script is doing, what can go wrong, and how to recover. It prevents the most common failure modes (infinite loops, stale state, missed transitions).

**Canonical state machine structure:**
```java
enum State {
    IDLE,
    WALKING_TO_BANK,
    OPENING_BANK,
    WITHDRAWING_ITEMS,
    WALKING_TO_RESOURCE,
    INTERACTING,
    WAITING_FOR_RESULT,
    RECOVERING,
    STOPPING
}

private State currentState = State.IDLE;

@Override
public void onLoop() {
    switch (currentState) {
        case IDLE -> evaluateAndTransition();
        case WALKING_TO_BANK -> handleWalkToBank();
        case OPENING_BANK -> handleOpenBank();
        // ... etc
        case RECOVERING -> handleRecovery();
    }
}
```

### 2.3 Prohibited Patterns

The following execution patterns are strictly forbidden:

| Pattern | Reason |
|---|---|
| `while(true) { ... }` blocking loops | Hangs the thread; violates tick model |
| `Thread.sleep(...)` | Blocks execution; use tick counters instead |
| Polling game state in a tight loop | Creates CPU load; misses state transitions |
| Acting on multiple game objects in one tick | Causes unpredictable interaction order |
| Assuming an action completes in one tick | Many actions span multiple ticks |

### 2.4 Tick-Aware Delays

When a script needs to wait (e.g., for an animation to complete, or an item to appear), it must use tick-count-based waiting, not sleep:

```java
private int waitTicks = 0;

// In onLoop():
if (waitTicks > 0) {
    waitTicks--;
    return;
}

// After an action that needs 3 ticks to resolve:
waitTicks = 3;
```

---

## 3. THREAD SAFETY RULES

Thread safety is the most critical correctness concern in Microbot. Violations cause race conditions, crashes, and client bans.

### 3.1 The ClientThread Rule

The RuneLite game client runs its state on the **ClientThread**. All game state — NPCs, objects, players, inventory, widgets — is owned by this thread.

**Absolute rules:**
- All game interactions MUST occur on or be dispatched to the ClientThread
- Never read mutable game state from background threads without synchronization
- Never mutate game state from any thread other than ClientThread
- Never block the ClientThread (no sleep, no heavy computation, no I/O)

### 3.2 Safe Dispatch Pattern

When you need to interact with game state from a background context:

```java
// ✅ Correct: dispatch to ClientThread
clientThread.invokeLater(() -> {
    // safe to interact with game state here
    player.interact("Attack");
});

// ❌ Wrong: accessing game state off-thread
new Thread(() -> {
    Rs2Npc npc = Rs2Npc.getNearestNpc("Goblin"); // UNSAFE
    npc.interact("Attack"); // UNSAFE
}).start();
```

### 3.3 Event Handler Rules

- `onGameTick`, `onNpcSpawned`, `onItemContainerChanged`, and similar event handlers fire on the ClientThread
- These handlers must return quickly — no blocking, no heavy work
- Delegate processing to state machine transitions, not inline in the handler

### 3.4 Entity Lifecycle

Game entities (NPCs, objects, ground items, players) can appear and disappear at any time. Never assume an entity that existed at tick N still exists at tick N+1.

```java
// ❌ Wrong: cache across ticks
NPC target = Rs2Npc.getNearestNpc("Goblin");
waitSomeTicks();
target.interact("Attack"); // target may be null/despawned

// ✅ Correct: re-query at time of use
Rs2Npc.interact("Goblin", "Attack"); // queries and acts atomically
```

---

## 4. QUERYABLE API RULES

The Queryable API is the primary interface for finding and interacting with game entities. It provides a fluent, readable, safe abstraction over raw RuneLite entity access.

### 4.1 Core Principles

- **Always use fluent query chains** — these communicate intent clearly and allow the API to optimize internally
- **Always filter before selecting** — narrow the candidate set before calling `nearest()` or `first()`
- **Never cache live game entities across ticks** — re-query at the point of use
- **Prefer readable chains over clever one-liners** — other developers (and AI) must understand the intent

### 4.2 Query Chain Structure

```
Rs2<Entity>
  .stream()           // open a query
  .filter(...)        // narrow candidates
  .filter(...)        // compose multiple filters
  .nearest()          // select the closest
  .ifPresent(e -> e.interact("Action")); // act safely
```

### 4.3 Entity-Specific Query Patterns

**NPCs:**
```java
// Find nearest attackable NPC with full health, not in combat
Rs2Npc.stream()
    .filter(npc -> npc.getName().equals("Cow"))
    .filter(npc -> npc.getHealthRatio() == -1) // full health
    .filter(npc -> npc.getInteracting() == null) // not in combat
    .nearest()
    .ifPresent(npc -> Rs2Npc.interact(npc, "Attack"));
```

**Objects:**
```java
// Find a bank booth that is reachable
Rs2GameObject.stream()
    .filter(obj -> obj.getName().equals("Bank booth"))
    .filter(obj -> Rs2Player.canReach(obj.getWorldLocation()))
    .nearest()
    .ifPresent(obj -> Rs2GameObject.interact(obj, "Bank"));
```

**Inventory:**
```java
// Check for item before using it
if (Rs2Inventory.contains("Lobster")) {
    Rs2Inventory.interact("Lobster", "Eat");
}
```

### 4.4 Null Safety

Always assume a query may return nothing. Use `Optional`-style patterns:

```java
// ✅ Safe: handles absent result
Optional<NPC> goblin = Rs2Npc.stream()
    .filter(n -> n.getName().equals("Goblin"))
    .nearest();

if (goblin.isEmpty()) {
    transitionTo(State.WALKING_TO_SPAWN);
    return;
}

Rs2Npc.interact(goblin.get(), "Attack");
```

---

## 5. PLUGIN MODEL

Every plugin in the Microbot ecosystem follows a strict structural contract.

### 5.1 Lifecycle Methods

All plugins MUST implement three lifecycle methods:

| Method | Trigger | Responsibility |
|---|---|---|
| `onStart()` | Plugin enabled by user | Initialize state, validate preconditions, configure settings |
| `onLoop()` | Each game tick | Evaluate state, execute one action, transition state |
| `onStop()` | Plugin disabled or error | Clean up resources, release locks, reset UI state |

### 5.2 onStart Requirements

`onStart` must:
- Validate that required preconditions are met (e.g., correct location, required items in inventory)
- Initialize the state machine to a safe starting state
- Log the startup context for debugging
- Fail fast and clearly if preconditions are not met

```java
@Override
public void onStart() {
    if (!Rs2Player.isLoggedIn()) {
        log.warn("Plugin started while not logged in — stopping");
        stop();
        return;
    }
    currentState = State.EVALUATING;
    log.info("Plugin started at {}", Rs2Player.getWorldLocation());
}
```

### 5.3 onLoop Requirements

`onLoop` must:
- Be fast (return in < 1 tick worth of computation)
- Execute at most one game action per call
- Always route through the state machine
- Never block

```java
@Override
public void onLoop() {
    if (!Rs2Player.isLoggedIn() || Rs2Player.isDead()) {
        transitionTo(State.RECOVERING);
        return;
    }
    switch (currentState) {
        // ... state handling
    }
}
```

### 5.4 onStop Requirements

`onStop` must:
- Cancel any pending timers or tasks
- Reset state to a safe default
- Not throw exceptions (log and swallow)

### 5.5 Recovery Logic

Every plugin must include a recovery state. Recovery handles unexpected situations — being teleported, running out of supplies, getting stuck.

**Recovery checklist:**
- Is the player in an unexpected location? → Walk to expected location
- Is the player in combat unexpectedly? → Wait or flee
- Is inventory in an unexpected state? → Bank and restart
- Has the plugin been in the same state too long? → Reset and retry

```java
private int stateTickCount = 0;
private static final int MAX_STATE_TICKS = 20; // ~12 seconds

private void transitionTo(State newState) {
    currentState = newState;
    stateTickCount = 0;
}

// In onLoop(), before state switch:
stateTickCount++;
if (stateTickCount > MAX_STATE_TICKS) {
    log.warn("State {} exceeded max ticks, recovering", currentState);
    transitionTo(State.RECOVERING);
    return;
}
```

### 5.6 Invalid State Handling

Plugins must never crash or throw unhandled exceptions during `onLoop`. Wrap state handlers defensively:

```java
case INTERACTING -> {
    try {
        handleInteracting();
    } catch (Exception e) {
        log.error("Error in INTERACTING state", e);
        transitionTo(State.RECOVERING);
    }
}
```

---

## 6. HUB EXTENSION MODEL

### 6.1 Separation of Concerns

The Hub and Core have a strict one-way dependency: Hub depends on Core; Core never depends on Hub.

```
RuneLite Client
      │
  Microbot Core   ← stable, versioned, tested
      │
  Microbot Hub    ← volatile, game-specific, replaceable
      │
  User Scripts    ← per-task, disposable
```

### 6.2 What Belongs in Hub vs Core

| Belongs in Core | Belongs in Hub |
|---|---|
| Generic entity query APIs | Game-specific interaction sequences |
| ClientThread safety utilities | Plugin UIs and configuration panels |
| Pathfinding primitives | Route definitions (e.g., "walk to GE") |
| Inventory/equipment state | Task-specific item lists |
| Stable, reused patterns | One-off or niche automation |

When a Hub pattern is used by 3+ unrelated plugins, evaluate promoting it to Core.

### 6.3 Prohibited Hub Behaviors

- Modifying or monkey-patching Core classes
- Reflectively accessing private RuneLite internals
- Registering global event hooks that persist after `onStop`
- Spawning unmanaged background threads
- Writing to disk in `onLoop`

### 6.4 Hub Plugin Lifecycle

Hub plugins are disposable. Any plugin that:
- Has not been updated in 2+ minor versions
- Duplicates functionality now in Core
- Has no active maintainer

…is a candidate for removal or archival.

---

## 7. AI DEVELOPMENT RULES

This section governs code generation and modification by AI systems (including Claude and similar tools) operating on the Microbot codebase.

### 7.1 Mandatory Compliance

When generating or modifying any Microbot code, an AI system MUST:

1. **Respect thread safety** — never generate code that accesses game state off-thread without proper dispatch
2. **Use state machines** — never generate polling loops or sleep-based automation
3. **Validate entities before interaction** — always null-check or use Optional patterns
4. **Assume game state is volatile** — never generate code that assumes an entity persists across ticks
5. **Include recovery behavior** — every generated plugin must have a RECOVERING state with meaningful logic
6. **Re-query before acting** — never reuse a previously queried entity reference

### 7.2 Code Generation Anti-Patterns

The following patterns are prohibited in AI-generated code:

```java
// ❌ Blocking loop
while (!Rs2Inventory.isFull()) {
    Thread.sleep(600);
    interact();
}

// ❌ Cached entity reuse
NPC target = findTarget();
for (int i = 0; i < 10; i++) {
    target.interact("Attack"); // stale!
}

// ❌ Off-thread game state access
CompletableFuture.runAsync(() -> {
    if (Rs2Player.isInCombat()) { ... } // unsafe!
});

// ❌ No recovery handling
@Override
public void onLoop() {
    bankItems();
    walkToResource();
    harvestResource();
    // No recovery, no state machine, no safety
}
```

### 7.3 Code Generation Best Practices

```java
// ✅ State machine with recovery
@Override
public void onLoop() {
    guardChecks(); // login, death, etc.
    tickCount++;
    if (tickCount > stateTimeout) handleTimeout();
    switch (state) {
        case BANKING -> handleBanking();
        case WALKING -> handleWalking();
        case WORKING -> handleWorking();
        case RECOVERING -> handleRecovery();
    }
}

// ✅ Query at point of use
private void handleWorking() {
    Rs2GameObject.stream()
        .filter(o -> o.getName().equals("Oak tree"))
        .nearest()
        .ifPresentOrElse(
            tree -> Rs2GameObject.interact(tree, "Chop down"),
            () -> transitionTo(State.RECOVERING)
        );
}
```

### 7.4 Scope Discipline

AI-generated changes must be minimal and targeted:
- Do not refactor unrelated code while implementing a feature
- Do not add abstractions that aren't immediately required
- Do not rename or reorganize files unless explicitly requested
- Prefer the simplest implementation that correctly solves the problem

### 7.5 Documentation Requirements

AI-generated plugins must include:
- A class-level Javadoc describing what the plugin does and its preconditions
- Inline comments on non-obvious state transitions
- A log statement at each state entry (debug level)

---

## 8. CORE DESIGN PRINCIPLES

These principles are listed in priority order. When two principles conflict, the higher-ranked one wins.

### 8.1 Stability > Cleverness

A simple, predictable solution that works for 10,000 ticks is better than an elegant solution that breaks on tick 237. Avoid:
- Dynamic strategy selection at runtime when a fixed sequence works
- Over-generalized abstractions that are hard to debug
- "Clever" bit manipulation or state compression that obscures intent

### 8.2 Safety > Speed

A slow automation that never crashes is better than a fast one that occasionally does something dangerous. Safety means:
- Validating preconditions before every action
- Handling the worst case, not just the happy path
- Logging unexpected states rather than silently ignoring them
- Preferring conservative timeouts over optimistic ones

### 8.3 Correctness > Optimization

An automation that does the right thing slowly is better than one that does the wrong thing fast. Do not optimize:
- Until correctness is established
- At the cost of readability
- Without a measured performance problem to solve

### 8.4 Readability as a First-Class Concern

Microbot code is read by:
- Developers debugging live issues
- Plugin authors extending existing scripts
- AI systems generating modifications

All three of these readers benefit from clear, self-documenting code. Use:
- Descriptive state names (`WALKING_TO_BANK`, not `STATE_3`)
- Explicit transition methods (`transitionTo(State.RECOVERING)`)
- Comments that explain *why*, not *what*

---

## 9. VERSIONING AND COMPATIBILITY

### 9.1 Core API Stability

Public Core APIs follow semantic versioning:
- **Patch:** Bug fixes with no API changes
- **Minor:** New APIs added; existing APIs unchanged
- **Major:** Breaking API changes; migration guide required

### 9.2 Hub Compatibility

Hub plugins declare a minimum Core version. Plugins targeting an older Core version:
- Must not use APIs introduced in newer versions
- Must be tested against the target version explicitly

### 9.3 Deprecation Policy

Before removing a Core API:
1. Mark it `@Deprecated` with a migration note
2. Maintain it for at least one minor version
3. Announce removal in the changelog

---

## 10. TESTING REQUIREMENTS

### 10.1 Unit Testable Logic

Business logic (state transitions, item calculations, condition checks) must be extractable and unit testable without a running game client.

Pattern: separate pure logic from game API calls:

```java
// Testable logic
public State evaluateState(boolean hasItems, boolean atBank, boolean isFull) {
    if (isFull) return State.BANKING;
    if (!hasItems) return State.BANKING;
    if (!atBank) return State.WALKING_TO_RESOURCE;
    return State.WORKING;
}

// Game-bound wrapper (not unit testable, but thin)
private void evaluateAndTransition() {
    transitionTo(evaluateState(
        Rs2Inventory.hasItem("Lobster"),
        Rs2Bank.isNearBank(),
        Rs2Inventory.isFull()
    ));
}
```

### 10.2 Recovery Testing

Every plugin's recovery path must be manually validated before release:
- Simulate running out of supplies mid-task
- Simulate being teleported away from task location
- Simulate the target entity despawning mid-interaction
- Simulate the plugin being stopped and restarted mid-state

---

*End of Microbot System Constitution v2.0*
*All subsystems, plugins, and AI-generated code must comply with this document.*
