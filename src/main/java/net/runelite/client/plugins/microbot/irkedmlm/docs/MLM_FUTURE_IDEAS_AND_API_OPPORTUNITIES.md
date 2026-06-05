# MLM Plugin Future Ideas + Microbot API Opportunities

Scanned via tools on sibling `../Microbot` (development branch) + existing MLM code + Java patterns in the project.

This document is research output for the query pointing at `MotherloadMineScript.java`. Ideas focus on:
- Enhancements **to the MLM plugin itself** (without breaking its human-like priority rules, session model, post-deposit repair gating, etc.).
- Brand new standalone plugins that could be added to Hub (leveraging the dynamic plugin discovery).
- Java / architectural patterns observed.

**Key APIs / Patterns Discovered (high value for MLM-like bots)**

## Caches + Queryable API (big win, one-shot streams!)
- `Microbot.getRs2TileObjectCache().query()` → `Rs2TileObjectQueryable` (extends `AbstractEntityQueryable`)
- Similar for `getRs2PlayerCache()`, `getRs2NpcCache()`, `getRs2TileItemCache()`.
- Builder style: `.withIds(...) .where(...) .within(...) .nearestReachable() .interact("Mine")` etc.
- **Critical**: Streams are one-shot. Never do `var q = cache.query()...; q.nearest(); q.nearestReachable();` (was a past crash in SackSession). Each terminal (nearest, toList, count, interact) consumes.
- Has `nearestOnClientThread()`, `toListOnClientThread()` helpers for safety.
- `getStream()` is tick-cached (avoids full scene scan every query).
- MLM already uses tile cache heavily in sessions (good). Opportunity: push more anti-crash / rockfall / "other players at spot" to player cache queries instead of raw.
- `fromWorldView()` for instanced areas (useful for future Leagues / other content).
- See: `api/tileobject/`, `api/player/`, `api/npc/`, `AbstractEntityQueryable.java` (many `where`, `nearest`, `interact` overloads).

## Walker Advances (Rs2Walker)
- `walkFastCanvas` (already used in MLM for short/precise moves inside chambers — correct choice per history).
- Full `walkTo` / web walking for long distance.
- Advanced: `pathahead/`, door handling (`Rs2DoorHandler`), transport, `Rs2Reachable`.
- MLM return-from-sack logic (walkFastCanvas to anchor or ladder bottom) could use more of the "passage" / "door" handlers if rockfalls or obstacles appear dynamically.
- Walker has built-in MLM rockfall handling in some paths.
- See `util/walker/`.

## Script Base + State Machines
- `Script` (extends Global): provides `scheduledExecutorService`, `run()` guard (blocking events, tutorial, pauseAllScripts, run energy/stam), `shutdown()`.
  - MLM's `run()` / `executeTask` + AtomicBoolean reentrancy guard + early !loggedIn returns is a hardened version of this.
- **StateMachineScript<S extends Enum<S>>**: opt-in for explicit states + `defineTransitions()` + `executeState(S)`.
  - Built-in support for agent server: `GET /debug/snapshot?script=YourScriptName` (full state + data).
  - Registry of running state machines.
  - Transitions are logged/observable.
  - Example in `statemachineexample/`: cycles SCAN_NPCS → ... using queryables for introspection.
  - AGENTS.md emphasizes this for 3+ phase scripts.
- Current MLM: custom `MLMStatus` + `dispatchByStatus` + 4 parallel `Session` subclasses (Mining/Sack/Hopper/Repair) with their own subStates + `isIdle()` / `tick` / `begin` / `reset`.
  - This gives "concurrent" behaviors (e.g. can check repair priority after deposit while a mining session might be active conceptually).
  - **Strong opportunity**: Hybrid — keep sessions for the sub-behaviors, but make the top-level `MLMStatus` + transitions use `StateMachineScript`. Gets free debug snapshots, easier hot-reload testing, consistent transition logging. The "post-deposit audit + repair only after successful non-fill deposit" priority logic maps cleanly to guarded transitions.
- See: `statemachine/StateMachineScript.java`, its AGENTS.md, the example, and `agentserver/handler/StateMachineDebugHandler.java`.

## Antiban & Humanization
- `Rs2Antiban`, `Rs2AntibanSettings` (takeMicroBreaks=false in MLM already per history).
- `applyMiningSetup()`, dynamic intensity, actionCooldown, naturalMouse, simulateMistakes/fatigue, moveMouseOffScreen, etc.
- `SessionFatigue`, `MouseFatigue`.
- MLM already configures a lot in `configureAntibanSettings()` (good).
- Opportunity: deeper integration, e.g. use `Rs2Antiban.actionCooldown()` more places, or hook fatigue into the humanPause / scheduleNext jitter.
- Base `Script.run()` already starts `SessionFatigue`.

## Other High-Value Utils
- `Rs2Player`: `isAnimating(ms)` (event-backed via lastAnimationTime from AnimationChanged — MLM history moved away from static timers to this + isMoving), `waitForXpDrop(skill, timeout, inventoryFullCheck)` (very reliable for skilling confirmation vs raw isAnimating).
  - MLM uses custom lastXp + delta + globalLastXpTime AtomicLong. Could layer `waitForXpDrop` for mining confirmation inside MiningSession.
- `Rs2Gembag` (MLM uses it for the "use gem bag" feature).
- `Rs2Combat` (spec handling — MLM wraps with hesitation + post-resume).
- `Rs2DepositBox`, `Rs2Bank` (MLM uses deposit box for sack empty).
- `Rs2Camera`, `Rs2Keyboard`, `Rs2Tab`.
- `Rs2Random` for all jitter (used everywhere in MLM).
- `Rs2Widget` for any UI interactions (rare in MLM).
- Events: `StatChanged`, `ChatMessage` (MLM already subscribes for sack-full flag via manual EventBus in plugin), `GameTick` (via broadcaster?).
- `GameTickBroadcaster`.
- `Rs2PlayerStateCache` for varps (MLM uses it for spec energy VARP 300).
- Reflection utils if you ever need to poke unexposed things (use sparingly).
- Agent server / hot-reload / `ScriptResultStore` / `microbot-cli` for automated testing of MLM scenarios (full login → start → results).
- `Perspective`, `LocalPoint`, `WorldPoint` + `Rs2LocalPoint` / `Rs2WorldPoint` / `Rs2WorldArea` / `Rs2Reachable` (MLM uses Perspective indirectly via sessions for floor checks; history had client-thread guards added).
- Overlays: `OverlayLayer.ABOVE_SCENE` (MLM and other tools use it), live vs snapshot driven (MLM overlay is pure snapshot — excellent for thread safety).
- Custom area definition (external tools can be used manually to generate meshes if desired).

## Java Patterns Observed in Microbot + MLM
- Heavy use of Lombok (`@Slf4j`, `@Getter`, `@Value` for immutable snapshots like `SessionSnapshot`).
- Guice `@Inject` + `@Provides` for config/overlay/script/plugin wiring.
- `Atomic*` for cross-thread flags (MLM's `executing` AtomicBoolean to prevent concurrent executeTask is textbook; also AtomicInteger for recoveryAttempts).
- `ScheduledExecutorService` per-script (base Script + MLM's main 600ms loop + per-session scheduleNext using nextActionMs gates).
- Client thread discipline: `Microbot.getClientThread().runOnClientThreadOptional(...)` / `invoke(...)` for Perspective/varbits/widgets/player world view / getLocalPlayer name etc. MLM has centralized some in script + pushed to sessions (history work).
- One-shot streams in queryables — must not reuse.
- EventBus manual registration in some plugins (avoids LambdaConversionException) — MLM plugin does this for ChatMessage sack flag.
- `volatile` for flags visible across threads (drawing, snapshot ref).
- Records not heavily used yet (Java 11 target? but records are 16+; project sticks to compat). Could use for small DTOs.
- Enums for states (MLMStatus, substates in sessions, Rocks in other miners).
- Builder / fluent for queryables.
- Defensive early returns + !loggedIn guards everywhere (prevents the breakhandler TimeoutException floods at startup that history fixed).
- Reproducible builds, shadow JAR per-plugin.

**Prioritized Ideas for the MLM Plugin Itself** (pointing at the referenced script)

1. **Hybrid StateMachineScript Refactor (High Impact, Low Risk to Core Logic)**
   - Keep the 4 `Session` classes + their rich sub-state machines (they solve the "multiple things can be happening" problem elegantly — repair priority only post-deposit, sack projection + chat flag, etc.).
   - Make the orchestrator `MotherloadMineScript` extend `StateMachineScript<MLMStatus>` (or a wrapper enum).
   - Define transitions explicitly (e.g. IDLE → {EMPTY_SACK if full, DEPOSIT_HOPPER if paydirt full, MINING otherwise}; MINING complete → determineNext... with the exact post-deposit audit rules).
   - Benefits: free `GET /debug/snapshot?script=MotherloadMineScript` (shows current status + any extra data you put in snapshot), automatic transition logging, easier to add "mid-run" observations, plays great with agent server / hot reload / testing protocol in docs.
   - The custom `dispatchByStatus` + early returns become the `executeState` + guarded transitions.
   - History already has excellent "priority" comments and logs — this would make them machine-readable too.
   - Do not touch the "repair only after successful non-filling deposit + hammer from crate first" rules.

2. **Deeper Queryable + Cache Usage + Remove Remaining Raw Client Calls**
   - In sessions and script, replace any remaining direct `client.get...` or old `Rs2GameObject` / `Rs2Npc` with cache `.query().where(spot.containsInArea...) .nearestReachable()`.
   - MLM already migrated a lot (tile cache for veins/struts/sack, player cache name push). Finish the job for consistency and the "Queryable is the way" pattern.
   - Use `.nearestOnClientThread()` where a terminal must be safe.
   - For anti-crash "is another player at my anchor/spot": use `Rs2PlayerCache.query().where(p -> !p.getName().equals(local) && distanceTo(anchor)<=2).count() > 0` (more efficient + cached than per-tick).
   - Add `Rs2TileItemCache` if we ever want to react to gems on ground (unlikely, but for completeness).

3. **Incorporate Rs2Player.waitForXpDrop + Stronger Mining Confirmation**
   - MLM already has excellent custom `lastXp` + `globalLastXpTime` + `onMiningXp` in session for timeout detection (better than pure isAnimating per history discussion of xporbdrops).
   - Layer `Rs2Player.waitForXpDrop(Skill.MINING, timeout, /*inventory check*/)` inside MiningSession's CLICKED/ARRIVING/CONFIRMED for the "did we actually start getting pay-dirt" confirmation. This is the "gold signal" mentioned in project notes.
   - Keeps the hybrid (XP + anim/moving + elapsed) that history settled on.

4. **Custom Area / Mesh Polish**
   - MLM core uses built-in rect/WorldArea meshes per MLMMiningSpot.
   - External tools can generate custom data if desired in future.
   - Future: named persisted areas or UI-driven custom spots could be added if desired.

5. **Special Attack / Spec Timing Polish (Follow-up to Recent Feedback)**
   - Keep all existing (hesitation, cooldown variance, pre/post resume, only when wearing pick, energy thresholds).
   - The recent "only when next to veins" guard (`isNextToOreVein` using cache query + distance <=2 + spot.contains) is exactly right.
   - Extra idea: only spec on "long" veins (upper level veins last ~36-40s vs lower 23-27s). Detect by checking if the selected/targetVein is in an upper mesh, or by simple height/plane. Or track average mining time per spot.
   - Config toggle: "Spec on upper only" or "Prefer spec before long veins".

6. **Config / UX / Docs Polish (Low Effort, High Value)**
   - Already improved in history, but: add sections or positions for "Advanced / Debug", "Humanization", "Spec".
   - Expose more of the internal tunables (e.g. humanPause ranges, shuffle interval, spec cooldown) behind debug or advanced section so power users can tune without code.
   - @ConfigInformation update with "See the testing protocol in docs/ for deposit-that-fills, full-sack-start, both-struts, upper cycles."
   - Consider a small in-game panel (not just overlay) for "MLM Controls" (pause, force repair check, dump current mesh, etc.).

7. **More Observability + Agent Server Hooks**
   - Expose richer data in the snapshot (current target vein, sub-state of all 4 sessions, last deposit success, projected sack, etc.).
   - Submit structured results via `ScriptResultStore` for automated test runs (e.g. "ran for 1h, nuggets gained, repairs done, no stucks").
   - Use `GameTickBroadcaster` if we want tick-precise without @Subscribe everywhere.

8. **Java / Perf / Safety Niceties**
   - Use `var` more (already some).
   - Make more small immutable DTOs as records if JDK allows (or keep @Value lombok for 11 compat).
   - Strengthen client-thread guards with asserts or wrappers in debug mode.
   - Consider extracting a `MLMConstants` or shared "MLM API" class so other plugins (the helper idea below) can reuse the spot enums, sack sizes, ore IDs, contains logic without duplication.
   - More defensive `Objects.requireNonNull` or null guards around miningSpot in hot paths.

**New Plugins We Could Build (Standalone, Auto-Discovered by Gradle)**

1. **Universal Area-Based Skiller / Spot Bot**
   - Can use external area capture tools for custom spots.
   - Config: resource type (rocks / trees / fishing / etc.), action ("Mine", "Chop", "Harpoon"), banking strategy (deposit box / bank / drop), antiban profile.
   - Reuses MLM's rectMesh / containsInArea pattern + queryable for targets + Rs2Player.waitForXpDrop for confirmation + anti-crash via player cache + natural mouse.
   - "MLM mode" that knows about pay-dirt → hopper → sack special case.
   - This generalizes the hard work done for MLM's upper chambers.

2. **MLM Visual Assistant / "Trainer Mode" (Non-Bot)**
   - Toggle in MLM or separate plugin.
   - Overlay shows: "Best vein right now" (using the exact selection + anti-crash logic from MLM script, but only highlights/tooltip, no click), estimated time to next rockfall, "recommended next action" (empty sack? repair? mine here?), live profit/XP/hr projection.
   - Can run alongside manual play or with MLM. Great for learning mechanics or when you want to be "present" but assisted.
   - Leverages the same `MLMMiningSpot` + session snapshot without the full automation path.

3. **Persistent Named Areas + "Area Library" Plugin**
   - Enhance area tools (or standalone): draw → name ("MLM-Upper-West-Wall", "Amethyst-Southeast") → save to config (Gson like ground markers) or `~/.microbot/areas.json`.
   - Exposes a small API / static registry other plugins can query: `AreaLibrary.get("MLM-Upper-West-Wall").mesh`.
   - MLM, the universal miner, amethyst plugin, etc. can offer "use saved area" dropdown instead of hardcoded.
   - Export buttons for Java snippet / JSON / for external tools.

4. **"Probe / Introspection" Dev Tools**
   - Minimal plugin using hot-reload: deploys tiny scripts that query caches for current MLM state (veins in area, player positions, varbit 5558 live, etc.) and dump via chat or `/state`.
   - Complements the existing `/debugger` skill and agent server.

5. **Other Content Using Same Patterns**
   - A "Giants' Foundry" or "Tempoross" or "Wintertodt" bot that uses similar session + priority + cache queries (many minigames have "deposit processed" + "repair" + multi-phase).
   - "Custom Rockfall / Obstacle Clearer" that generalizes MLM's rockfall memory + clearing logic.
   - Leagues-specific transport / area plugins using the new LeaguesTransport and worldView awareness.

6. **Quality-of-Life Companions**
   - "MLM QoL": auto open gem bag on login if in MLM, highlight your current assigned spot on minimap/world, one-click "reset my blacklisted crates", chat commands for the running MLM script.
   - Integrate with existing `qualityoflife` plugin in the hub.

**Risks / Things to Watch (from project notes + scans)**
- Always client-thread for Perspective, varbits, widgets, getLocalPlayer world view, WorldView.
- Queryable streams: one terminal per builder.
- No static leakage across restarts (MLM uses instance + push from orchestrator).
- Test the exact user scenarios: start with sack full, deposit-that-fills-then-residual-paydirt, both struts, upper-only runs, spec timing.
- Version bump + targeted build (`-PpluginList=IrkedMLMPlugin`) + generatePluginsJson (JDK 11) for releases.
- Keep the "human like but rule-abiding" (no mid-mine repair, repair only post-successful deposit + hammer first, etc.).

**Next Steps Recommendation**
- Start with #1 (StateMachine hybrid) — gives the biggest "this feels like a first-class Microbot plugin using modern APIs" lift while directly addressing history pain points ("lots of state arrays", "stuck from time to time", debuggability).
- Use the agent server + `microbot-cli scripts deploy/reload` + the MLM_TESTING_PROTOCOL.md for rapid iteration without full client restarts.
- For any new plugin, follow the CLAUDE.md template: per-plugin source set, @PluginDescriptor with MOCROSOFT prefix + constants, docs/README.md + assets, version static, etc.

This scan shows MLM is already one of the more advanced plugins in the hub (sessions, strict priorities, heavy caching, human pauses everywhere, cache queryables, post-deposit exact logic). The opportunities are mostly "make the advanced patterns first-class and reusable".

Generated after tool-assisted exploration of the Microbot source tree and cross-referencing with the current MLM implementation.
