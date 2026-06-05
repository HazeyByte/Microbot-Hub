# Motherload Mine Integration Testing Protocol

Following the extensive architectural overhaul and stabilization of the Motherload Mine plugin, this protocol outlines the mandatory integration testing scenarios to be executed in a live client environment.

## 1. Threading and Stability
- **Execution Crash Test**: Run the bot for a full inventory/sack cycle. Verify no `RuntimeException` or `ConcurrentModificationException` occurs in the logs during session transitions.
- **Ladder Transition Test**: Monitor floor transitions (`climbUp`/`climbDown`) in the logs. Ensure the bot waits for the floor to change (`isUpperFloor()`) before proceeding with further actions.

## 2. Navigation & Coordinates
- **Area Accuracy Test**: Select `WEST_UPPER` and `EAST_UPPER` separately. The bot must walk to the anchor point and begin mining within the respective cluster without "bouncing" between areas.
- **Walkable Tile Guard**: In crowded mining spots, ensure the bot selects only veins whose surrounding tiles are walkable (no "unreachable" click attempts).

## 3. Sack Emptying & Deposit Box
- **Deposit Box Workflow**: Ensure the bot walks to the deposit box, opens it, and deposits ore correctly. Verify that it checks for gem bag emptying *once* per session.
- **Viewport Check**: Ensure the deposit box interface does not occlude the sack interaction point, causing the bot to click the wrong object.

## 4. Repair Session Rework
- **Repair Gate**: Both struts must be broken, the bot must *not* be actively mining, and no other players should be within 4 tiles of the waterwheel area.
- **Hammer Cleanup**: If the bot fetches a hammer, it *must* drop it after completing the repair.
- **Climb-Up**: If the bot was mining upstairs, it *must* climb the ladder to return to the original spot after repair.
- **Repair Randomization**: Ensure that when repairing, the bot sometimes repairs only one strut and sometimes repairs both (50/50 gate).

## 5. Anti-Ban & Performance
- **Micro-Breaks**: Observe the logs for `takeMicroBreakByChance()` during idle states.
- **Special Attack Throttling**: Verify that pickaxe special attacks have a 60-second cooldown and are only used when energy is sufficient.
- **Overlay Performance**: Monitor for FPS drops or lag during overlay rendering, ensuring all game-state queries are performed in the script thread and not in `render()`.

## 6. Regression Scenarios
| Scenario | Expected Behavior |
| :--- | :--- |
| Both struts break while mining | Finish current vein, deposit hopper, *then* repair |
| Both struts broken, players near waterwheel | Return to mining, defer repair |
| Both struts broken, waterwheel clear | Repair 1 or both randomly |
| Repair done, was upstairs | Drop hammer, climb ladder, return to spot |
| Repair done, was downstairs | Drop hammer, walk back to spot |
| User brings own hammer | Never dropped, `ownsHammer` false |
| Only 1 strut broken | Do NOT enter repair; continue mining |
