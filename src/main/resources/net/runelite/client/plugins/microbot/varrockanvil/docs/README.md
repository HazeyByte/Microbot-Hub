# StickToTheScript's Varrock Anvil Smither

![preview](assets/icon.png)

Smiths at the three anvils south of **Varrock west bank**, including Sailing ship keels.

---

## Features

| Feature | Description |
|---|---|
| **All bar types** | Bronze through runite, plus every item the anvil offers for each. |
| **Sailing keels** | Keel parts and large keel parts, bronze through dragon. |
| **Imcando hammer** | Both variants, in the inventory or equipped. Wearing one frees an inventory slot for an extra bar. |
| **Banking** | Deposits output and withdraws a full load at Varrock west bank. |
| **Recovery** | Survives disconnects, stale interfaces and odd inventories; gives up with a clear reason rather than looping. |
| **Five settings** | Bar, item, log out, overlay, verbose logging. Everything else is a sane default. |

---

## Requirements
- Microbot RuneLite client
- A hammer — plain or either Imcando variant — and the selected bars, in the bank or on you
- Start at or near Varrock west bank

---

## Configuration

- **Bar type** — the bar to use. For keel parts this also picks the keel's metal.
- **Item** — what to make at the anvil.
- **Log out when finished** — log out when the script stops.
- **Show overlay** — the on-screen panel with state, runtime, XP and rates.
- **Verbose logging** — log every state decision, not just milestones. Useful for bug reports.

Everything else is a fixed sane default: the anvil quantity is set to All, a carried Imcando hammer
is worn automatically, the camera is framed once at startup, antiban is on and micro breaks are off.

---

## Notes on Sailing keels

| Tier | Smithing | Keel parts | Large keel parts |
|---|---|---|---|
| Bronze | 10 | 5x Bronze bar | 5x Bronze keel parts |
| Iron | 22 | 5x Iron bar | 5x Iron keel parts |
| Steel | 38 | 5x Steel bar | 5x Steel keel parts |
| Mithril | 56 | 5x Mithril bar | 5x Mithril keel parts |
| Adamant | 74 | 5x Adamantite bar | 5x Adamant keel parts |
| Rune | 86 | 5x Runite bar | 5x Rune keel parts |
| Dragon | 94 | *Dragon Forge only* | 2x Dragon keel parts |

Note the names: adamantite bars make **Adamant** keel parts and runite bars make **Rune** keel parts.

There are no silver or gold keel parts — picking one is rejected at startup. Dragon is supported for
**Large keel parts only**; regular dragon keel parts are made from dragon metal sheets at the Dragon
Forge, which no anvil can do.

Large keel parts consume regular keel parts, not bars, so bank the small ones first.

---

## Disclaimer
This script is intended for use within the **Microbot RuneLite Client** only.  
Use of automation software in Old School RuneScape is against Jagex’s rules and can result in penalties to your account.  
Use at your own risk.

---

## Changelog
### 1.3.3
- **Click through the welcome screen.** The "Click here to play" screen (widget 378) sits on top of a session the client already reports as `LOGGED_IN`, so the script thought it was in-game and every click landed on the overlay.
- **Fix the startup lag**, which was self-inflicted. The camera moved in 3–6 hops per axis and each `Rs2Camera` move is already 10 blocking client-thread steps internally, so framing cost hundreds of round trips; the visibility check re-resolved both tiles and ran three times per loop pass; and the XP capture busy-looped six times with `Thread.sleep(150)` between blocking reads, stalling the first tick for ~1s.
- Tiles are now resolved once, the visibility check runs once per pass, the initial framing is two eased moves per axis, corrections are single nudges, and the XP read retries across ticks instead of blocking inside one.

### 1.3.2
- Set the camera **yaw** as well as zoom and pitch. Without a yaw the view could face anywhere, so the client still had to turn toward whatever it was about to click. The bank and anvil sit on a north-south line, so looking along the east-west axis puts both on the screen's wider dimension.
- Move the camera at a human pace. `Rs2Camera` completes *any* distance in 220–780 ms, so even its eased path whips a half-turn round in under a second, and `turnTo()` is worse — it forces `setCameraSpeed(3f)` and holds an arrow key. The camera now moves in several randomised hops with pauses between them.
- Randomise the framing per session: east or west, jittered angle, and a randomised zoom, pitch and hop count — then verify both the bank and the anvil are genuinely on screen and widen until they are, so the randomness never costs visibility.
- Frame on arrival at the bank or anvil rather than at startup, since the tiles have to be in the loaded scene to test whether they are visible.

### 1.3.0
- **Camera actually works now.** The old setup zoomed *in* (284–400, where lower means further out), picked a pitch anywhere from horizon to top-down, snapped instantly, and swallowed every error in a `catch (Throwable ignored)`. It now zooms out, looks down, and eases into place over a randomised duration.
- **Stop the camera whipping between the bank and the anvil.** Every object click turns the camera when the target is off screen. Zoomed in at a low pitch, each leg of the walk put the destination off screen, so the camera snapped round on every click. Framed properly, both ends stay in view and that never fires.
- **Bank a spare hammer.** With an Imcando hammer equipped, a plain hammer in the inventory was kept forever, wasting a slot — the deposit list excluded every hammer unconditionally regardless of what was worn.
- **Dragon keels supported.** Large dragon keel parts are 2 Dragon keel parts at an ordinary anvil, so they belong here. Regular dragon keel parts still need the Dragon Forge and are rejected with an explanation.
- **Correct keel quantities and levels.** Dragon takes 2 materials; every other tier takes 5. Keel levels (10/22/38/56/74/86/94) are per-tier and differ from the bars' own smelting levels, which is what was being checked.
- Removed blurite.
- Config cut back to five settings. Make-all, wearing the Imcando hammer, camera setup and antiban are simply always on; micro breaks always off.

### 1.2.0
**Fixes for getting stuck**
- Reject impossible bar/item pairings at startup instead of discovering them at the anvil. Picking a metal with no keel parts (blurite, silver, gold) used to loop between the bank and the anvil forever.
- Fix the keel metal names. Adamantite bars make **Adamant** keel parts and runite bars make **Rune** keel parts — the old code looked for "Adamantite"/"Runite keel parts", which do not exist, so those two tiers could never be found and span in recovery.
- Give up after 5 failed attempts in a row with a message naming the likely cause, rather than retrying forever.
- Enforce the make-session timeout that was being calculated but never checked, so an animation that never ends no longer parks the script in SMITHING.
- Add a 4-minute no-progress watchdog that routes to recovery (suppressed during micro breaks).
- Re-derive everything from the world after a disconnect, logout or world hop instead of resuming a remembered session.
- Guard against overlapping ticks, so a slow tick can't drive the interface from two threads.

**Fixes for correctness**
- Restore the "All" quantity click, dropped in the rewrite. Without it the game's remembered quantity was used, which is often 1 — one item per trip to the anvil.
- Deposit output when making large keel parts. That branch previously deposited nothing, so the inventory filled up and stalled.
- Select bronze wire, oil lamps, iron spits, bullseye lamps and studs by name. They share generic interface slots whose index shifts per metal, so the old fixed indexes could smith the wrong item.
- Add crossbow limbs, which has a real interface slot and was simply missing.
- Remove dragon metal sheets: dragon keel parts are made at the Dragon Forge, not an anvil.

**New**
- Startup preflight: clears stale dialogues, checks your Smithing level once with a clear message, and wears a carried Imcando hammer so it stops using an inventory slot.
- Config reorganised into **What to smith**, **Behaviour**, **Stop conditions** and **Overlay & logging**, with individual toggles for make-all, wearing the Imcando hammer, camera setup, antiban and micro breaks.
- Stop after a set number of items or minutes.
- Overlay and stats can be turned off independently; the overlay now names the exact item ("Adamant keel parts", not "Keel parts").
- Logs collapse repeated lines, tag each line with the state it came from, and separate milestones from verbose detail.

### 1.1.0
- Accept both Imcando hammer variants — main-hand (weapon slot) and off-hand (shield slot) — in the inventory **or equipped**. Previously only a plain hammer counted, so the script banked an inventory Imcando hammer on Deposit-All and never saw an equipped one, stopping with "Could not find hammer in bank" on an account that could smith fine.
- An equipped hammer no longer reserves an inventory slot, so a full extra material slot is withdrawn per trip.
- Add Sailing keel parts and large keel parts as smithable objects.
- Rewrite the script as a DETERMINE/BANKING/SMITHING/RECOVERY state machine with real walk legs to both the anvil and the bank, plus stuck recovery.
- Track XP/hr and items made in the overlay.

### 1.0.3
- Add icon PNG

### 1.0.2
- Fix out of bars logic.
- Fix hammer selection logic to ensure the correct hammer is taken from the bank.

### 1.0.1
- Increase timeout timer from 5 seconds to 10 seconds when waiting for the anvil interface to open.
- Increased wait time for XP drop from 4.5 seconds to 7.5 seconds.

### 1.0.0
- Initial Release