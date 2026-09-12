# Motherlode Mine (irkedMATT)

Automates the Motherlode Mine: mines pay-dirt, deposits it at the hopper, empties the sack at
the deposit box, repairs the water-wheel struts, and tracks ores/nuggets/XP in an overlay.

## Requirements

- A pickaxe you meet the Mining level for, equipped or in your inventory.
- Nothing else. A gem bag and an Imcando hammer are optional and off by default.

## Features

- **Mining spots**: West Lower, West Mid, South East, South West, and the two upper chambers
  (West Upper, East Upper). Upper chambers use hand-defined tile meshes for the wall veins.
- **Sack tracking**: projection-aware sack count that stays accurate through varbit lag right
  after a deposit, so it empties at the right time instead of a cycle early or late.
- **Water-wheel repair**: fetches a hammer from the supply crate when needed and repairs broken
  struts. Defers to another player already at the wheel, but reclaims the job if the struts stay
  broken (an idle bystander won't leave the bot stuck).
- **Imcando hammer**: with *Use Imcando Hammer* on, the startup preflight equips your own Imcando
  in the off-hand slot — from equipment, inventory (wielding or swapping the main-hand variant),
  or a one-off bank-chest withdraw. Equipped off-hand it survives Deposit-All, so repairs never
  need a crate trip. No Imcando anywhere and it safely falls back to a crate hammer.
- **Startup preflight**: one guarded pass establishes the gem bag, the hammer, and the locked
  gem-bag slot before mining, then never re-runs. Every check safe-degrades (missing gem bag → run
  without one; slot won't lock → Deposit Items for the session) instead of stalling.
- **Pickaxe special attack**: uses dragon / infernal / crystal pickaxe specials while mining.
- **Gem bag** support and optional **gem dropping**.
- **Upstairs hopper** support once unlocked (still climbs down to empty the sack and repair).
- **Anti-crash**: on the lower floor, optionally avoids veins another player is standing on.
- **Humanization**: optional layer of randomized pauses, hesitation, spot jitter, and imperfection.

## Setup

1. Have a pickaxe (equipped or in inventory) you meet the Mining level for.
2. Open the config and pick your **Mining Area** (General) and **Sack Size** (Hopper & Sack).
3. Choose a **Deposit Method** (Deposit). See *Deposit & gem bag* below if you want Deposit All
   together with a gem bag.
4. Start the plugin. Stop it from the plugin panel at any time — it saves lifetime totals on stop.

## Configuration

### General / Mining

| Setting | What it does |
|---------|--------------|
| Mining Area | Primary mining location. |
| Anti Crash | Lower floor only: reselect a vein when another player is standing on it. Off by default. |

### Gem Bag

| Setting | What it does |
|---------|--------------|
| Use Gem Bag | Withdraw the bag if needed, keep it locked in inventory slot 1, and empty it at the deposit box when a gem slot fills. |
| Drop Gems | Bin uncut gems instead of banking them, to save inventory space. They come out of the sack with the ore, so they are dropped during the sack trip. Ignored while *Use Gem Bag* is on. |

### Hopper & Sack

| Setting | What it does |
|---------|--------------|
| Sack Size | Standard (108) or Upgraded (189). Corrected automatically either way — see *Notes*. |
| Use Upstairs Hopper | Deposit at the upper hopper once you have unlocked it. |

### Repair

| Setting | What it does |
|---------|--------------|
| Repair Struts | Repair broken struts yourself, on deposit trips only. On by default. |
| Use Imcando Hammer | You are supplying your own Imcando hammer, so never fetch one from the crate. |
| Wait for Others to Repair | Don't repair over someone already stood at the wheel — wait and let them finish. If they turn out to be idle, the job is reclaimed after a randomised patience window. On by default. |
| Hop World When Frozen | With Repair Struts off: hop to a busier world if the wheel stays frozen past the grace period. |
| Frozen Grace (seconds) | How long to wait before hopping. Default 120. |

### Deposit

| Setting | What it does |
|---------|--------------|
| Deposit Method | Deposit Items (per-ore, always safe) or Deposit All (needs the slot lock for the gem bag). |

### Overlay

| Setting | What it does |
|---------|--------------|
| Compact Mode | Shrinks the panel to status, sack progress, nuggets and the two rates. |
| Show Ore Totals | Per-ore session totals with item icons. |
| Show Performance | XP/hr and GP/hr. |
| Show Phase Timer | Footer timer — see *Notes and limitations* for what it actually counts. |

### Humanization

| Setting | What it does |
|---------|--------------|
| Human-like behavior | Master toggle for the humanization layer. Turn off for maximum speed. |
| AFK Park Side | Which screen edge the cursor parks off during AFK bouts — `None` (never leaves the client), `Left`, `Right`, `Top`, `Bottom`, or `Random` (one edge picked per login). |
| Mouse Activity | What the cursor does during a bout — see below. |

### Mouse Activity modes

These differ on two axes, not one: how often the cursor leaves the canvas, and how busy it is while
on it.

| Mode | Parks off-screen | While on-screen |
|------|------------------|-----------------|
| AFK (default) | Every bout | Nothing — no vein pre-hover, no inventory checks |
| Balanced | At this login's personality rate (~45-88%) | Normal pre-hover, late hover, occasional inventory check |
| Active | Seldom (~12%) | Pre-hovers and checks the inventory roughly twice as often as Balanced |

Set **AFK Park Side** to `None` and the "park" outcome keeps the cursor inside the client instead,
resting where the last click left it. The hover/glance rates still come from your rolled per-login
personality — the mode scales them rather than replacing them, so a relaxed player stays relaxed
even on Active.

## Deposit & gem bag

- **Deposit Items** (default) deposits each ore type by id. Always safe — it never touches the gem
  bag, whether the bag is open or closed. Use this if you don't lock inventory slots.
- **Deposit All** uses the deposit box's *Deposit All* button. An open gem bag would be swept into
  the bank by it, so turn on **Use Gem Bag**: at startup the bot places the bag in slot 1 and locks
  that slot at the **bank chest** (the deposit box can't lock slots), enabling the game's *Lock
  inventory slots* feature if needed, so Deposit All excludes it. The lock is a game setting that
  **persists across logins**, so this is a one-time trip — later sessions read the locked state and
  go straight to mining with no bank visit. If locking isn't possible, the run safely degrades to
  **Deposit Items** for that session rather than risking the bag. If the slot ever becomes unlocked
  mid-run, the next deposit aborts to recovery and re-establishes the lock.
- The **bank chest** is used only for withdrawing (gem bag / Imcando) and the one-time slot lock;
  all routine sack emptying goes through the **deposit box**.
- The gem bag is only emptied when a gem slot is actually full — a partial bag keeps accumulating
  across sack cycles.

## Notes and limitations

- Nugget upgrades (bigger sack, upstairs access) are left to the player — the bot does not spend
  nuggets.
- **Sack size corrects itself.** Setting *Upgraded* on an account that still has the 108 sack used to
  wedge the deposit loop: the varbit caps at 108 while the bot believed 189, so "sack full" never
  became true and it kept re-offering pay-dirt to the hopper. Now, if a wheel is turning and the
  hopper still refuses an entire load, that refusal is treated as proof the sack is full and the real
  capacity is recorded — so a wrong setting costs one rejected deposit, not a stuck run.
- **Full sack mid-deposit** runs: drop the carried pay-dirt → empty the sack → collect the pay-dirt
  again → deposit it at your configured hopper → back to mining. The pay-dirt is always dropped
  first, even when ores are also being carried: the sack cannot be emptied while pay-dirt occupies
  the slots the withdrawn ore needs.
- **Dropped pay-dirt is picked back up.** Emptying the sack needs free inventory slots, so a full load
  of pay-dirt is dropped first. Once the sack is clear the pile is collected again before the bot
  walks off. It is written off if it has been on the floor too long to still exist, if the inventory
  has no room, or if the run has already moved away from the spot.
- The overlay's **Nuggets** figure is what you have gained **this session**, counted as each batch
  lands in your inventory. It is correct under either deposit method, and nuggets you were already
  carrying when you started are not counted. It is deliberately not your total owned — your banked
  nuggets are not included.
- Lifetime totals (ores, XP, runtime, sacks emptied, nuggets) persist across sessions via the client
  config. The slot lock and the equipped hammer are read from the game each start rather than stored.
- Upstairs mining still requires climbing down to empty the sack and to repair, because both are
  lower-floor only.
- The overlay's footer timer is **not** a "time to next vein" countdown — MLM veins collapse on a
  random per-ore roll, so no such countdown exists to display. While mining it counts up since the
  last Mining XP drop (it turns amber past 20s, which usually means the bot is walking or stuck);
  the rest of the time it counts how long the current phase has been running.
- The humanization layer treats mining and chores differently on purpose. Mining is the AFK half:
  long gaps, parked cursor. Hopper trips, sack emptying and wheel repairs are run on a compressed,
  focused timing profile without the multi-second distraction tail, because that is how a player
  actually behaves when a chore interrupts an AFK loop.
- Recovery never stops the plugin: repeated stalls trigger a hard reset back to a known safe tile
  rather than halting the run.
