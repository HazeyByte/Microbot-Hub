# Blastoise Furnace

Automates bar smelting at the **Blast Furnace** in Keldagrim. Runs ore from the bank onto the
conveyor belt, waits for the dispenser, cools and collects the bars with ice gloves, banks, and
repeats — managing the coffer and (for low-level accounts) the foreman fee along the way.

## Supported bars

| Bar | Notes |
|-----|-------|
| Steel | iron + coal |
| Gold | goldsmith gauntlets required; coal bag not used |
| Mithril / Adamantite / Runite | primary ore + coal (coal bag required) |
| Hybrid Mithril / Adamantite / Runite | as above, but tops the load with gold ore for extra Smithing XP |

The script reads the coal already in the furnace each trip and picks how to fill the next load
(double coal / coal + primary / primary only) so the furnace stays fed rather than starving on
coal or ore.

## Requirements

- **Ice gloves** or **smiths gloves (i)** equipped or in the bank (needed to take hot bars).
- **Coal bag** in inventory or bank (all bars except gold).
- **Goldsmith gauntlets** for gold / hybrid bars.
- **Stamina and/or energy potions** in the bank (run management).
- **Coins in the bank** for the coffer, plus 2,500 gp per 10 min for the foreman if under 60 Smithing.
- Sufficient Smithing level for the chosen bar.

## Configuration

- **Bars** — which bar to smelt.
- **Human-like behaviour** *(on by default)* — reaction delays, the shared Rs2Antiban smithing
  profile (fatigue, attention span, play-style timing, natural mouse), occasional camera glances,
  and occasional simulated mistakes.
- **Coffer top-up (coins)** — how many coins to keep in the coffer. Only refilled when the coffer
  actually runs empty (default 72,000).

## Overlay

Shows current state, bars made, Smithing XP gained, XP/hr, live coffer balance, and runtime.

## Safety / failsafes

- Walks out via the stairs (stopping coffer drain) and stops when the bank runs out of ore.
- Stops with a message if a required item (coal bag, gauntlets, ice/smiths gloves) is missing.
- Never leaves the character idle mid-run with hot bars — micro-breaks are disabled on purpose,
  since standing idle in the furnace drains the coffer.

## Source files

- `BlastoiseFurnacePlugin.java` — lifecycle, inventory/coal-bag chat tracking.
- `BlastoiseFurnaceScript.java` — the BANKING ↔ SMITHING state machine and feeding logic.
- `BlastoiseFurnaceConfig.java` — configuration.
- `BlastoiseFurnaceOverlay.java` — the stats overlay.
- `enums/Bars.java`, `enums/State.java` — bar definitions and script states.

*Created by Fishy. Updated by Acun, Wassuppzzz.*
