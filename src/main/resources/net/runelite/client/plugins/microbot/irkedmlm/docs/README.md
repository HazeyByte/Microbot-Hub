# Motherlode Mine (irkedMATT)

Automates the Motherlode Mine: mines pay-dirt, deposits it at the hopper, empties the sack at
the deposit box, repairs the water-wheel struts, and tracks ores/nuggets/XP in an overlay.

## Features

- **Mining spots**: West Lower, West Mid, South East, South West, and the two upper chambers
  (West Upper, East Upper). Upper chambers use hand-defined tile meshes for the wall veins.
- **Sack tracking**: projection-aware sack count that stays accurate through varbit lag right
  after a deposit, so it empties at the right time instead of a cycle early or late.
- **Water-wheel repair**: fetches a hammer from the supply crate when needed and repairs broken
  struts. Defers to another player already at the wheel, but reclaims the job if the struts stay
  broken (an idle bystander won't leave the bot stuck).
- **Pickaxe special attack**: uses dragon / infernal / crystal pickaxe specials while mining.
- **Gem bag** support and optional **gem dropping**.
- **Upstairs hopper** support once unlocked (still climbs down to empty the sack and repair).
- **Anti-crash**: on the lower floor, avoids veins another player is standing on.
- **Humanization**: optional layer of randomized pauses, hesitation, spot jitter, and imperfection.

## Setup

1. Have a pickaxe (equipped or in inventory) you meet the Mining level for.
2. Open the config and pick your **Mining Area** and **Sack Size** under *Core Settings*.
3. Lock any inventory slots you want to keep if you enable **Use Deposit All**.
4. Start the plugin.

## Config highlights

| Setting | What it does |
|---------|--------------|
| Mining Area | Primary mining location. |
| Sack Size | Standard (108) or Upgraded (189); auto-detects increases mid-run. |
| Use Upstairs Hopper | Deposit at the upper hopper once unlocked. |
| Repair Struts | Auto-repair broken water-wheel struts. |
| Use Gem Bag | Empty a full gem bag at the deposit box. |
| Drop Gems | Drop uncut gems at the vein to save space. |
| Human-like behavior | Master toggle for the humanization layer. |
| Debug Mode | Verbose console logging of state transitions and routing decisions. |

## Notes

- Nugget upgrades (bigger sack, upstairs access) are left to the player — the bot does not spend
  nuggets.
- The overlay's nugget total is the live inventory + bank count.
