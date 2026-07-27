# irkedFarmer

An all-in-one farming plugin. It builds a **queue of independent farming runs** from the activities you
enable, prepares an inventory for **one run at a time** (never overflowing 28 slots), executes each run,
and banks between runs.

It replaces three standalone plugins — **Farm tree runner**, **Herb runner**, and **Birdhouse runner** —
with one plugin and one config.

## Activities

| Run | What it does |
|-----|--------------|
| **Tree run** | Regular tree patches (Gnome, Falador, Lumbridge, Varrock, Taverley, Farming Guild, Auburnvale) + Prifddinas crystal tree |
| **Fruit tree run** | Fruit patches (Gnome, Tree Gnome Village, Brimhaven, Catherby, Farming Guild, Lletya, Kastori) |
| **Hardwood run** | Fossil Island ×3 + Avium Savannah |
| **Herb run** | Herb + flower + allotment patches across the enabled regions |
| **Birdhouse run** | The four Fossil Island birdhouses |

Enable any combination in **General**. The plugin runs them in order, banking between each — so enabling
several at once never overflows your inventory (each run only prepares what *it* needs).

## How it works

- **Per-run inventory planning.** Each run computes exactly what it needs (saplings, tools, compost,
  teleports, optional protection payment) and validates it against 28 slots *before* banking. Overflow is
  structurally impossible.
- **Best-effort protection.** Paying a gardener to protect a tree is optional and never aborts a run — if
  the payment item is missing, it plants unprotected and moves on.
- **Best-effort compost.** If compost isn't available it plants without it rather than stalling.
- **Variant/charge aware.** Graceful recolours, and every charge of a skills necklace / digsite pendant /
  teleport crystal, are recognised (worn or carried).

## Setup

1. Bank the supplies for the runs you enable (saplings/seeds, tools, compost, teleport items, logs, etc.).
2. Enable the activities in **General** and pick your saplings/seeds in each section.
3. Start the plugin near a bank.

## Requirements

- Members world.
- Quests/access for the patches you enable (e.g. Bone Voyage for Fossil Island, Song of the Elves for
  Prifddinas, Mourning's End Part I for Lletya).
- Appropriate Farming level for the seeds/saplings you select.

## Notes

- Config group is `irkedfarmer` — settings do **not** carry over from the old standalone plugins.
- The queue banks between every run in this version.
