# ⛏️ Motherlode Mine (irkedMATT) — v1.0.0 — testers wanted

A full Motherlode Mine bot: mines pay-dirt, runs it to the hopper, empties the sack at the deposit box, and repairs the water wheel when it breaks. It's been through a lot of live iteration and I'd like more eyes on it before I call it stable.

**What it does**

⬥ **All six mining spots** — West Lower, West Mid, South East, South West, and both upper chambers (West Upper, East Upper)
⬥ **Sack tracking that's actually accurate** — the sack varbit lags right after a deposit, so the count is projection-aware. It empties at the right time instead of a cycle early or late
⬥ **Water-wheel repair** — fetches a hammer from the supply crate when it needs one. If another player is already stood at the wheel it leaves them to it rather than clicking the same struts, and reclaims the job if they turn out to be idle
⬥ **Imcando hammer support** — supply your own and it gets equipped in the off-hand at startup, where it survives Deposit-All. No more crate trips
⬥ **Gem bag** — optional, with a one-time inventory-slot lock so Deposit All can't sweep it into the bank. Only empties it when a slot is actually full
⬥ **Pickaxe specials** — dragon / infernal / crystal
⬥ **Upstairs hopper** once you've unlocked it
⬥ **Anti-crash** (optional) — won't fight another player for a vein on the lower floor
⬥ **Session overlay** — sack progress, nuggets, per-ore totals, XP/hr and GP/hr, with a compact mode
⬥ **Humanisation** — randomised pauses, varied mouse behaviour, per-login personality. Mining is treated as the AFK half; hopper trips, sack emptying and repairs run on a snappier "I'm actually looking at the screen" profile, because that's how people actually play it

**Setup**

1. Have a pickaxe you meet the Mining level for — equipped or in your inventory and be in the Motherload Mine area(Due to walker issues). 
2. Pick your **Mining Area** (General) and **Sack Size** (Hopper & Sack — Standard 108 or Upgraded 189)
3. Pick a **Deposit Method** (Deposit):
 • **Deposit Items** (default) — per-ore, always safe, never touches a gem bag
 • **Deposit All** — faster, but if you use a gem bag turn on **Use Gem Bag** too. It'll do one bank-chest trip to lock the bag's slot, and that lock persists across logins so it never needs to go back
4. Start it. Stop it from the plugin panel whenever — lifetime totals save on stop

Everything else has a sensible default. You don't need a gem bag or an Imcando hammer.

**What I'd like tested**

Anything, but especially:

⬥ **Spots other than West Upper** — that's the one I've run most. The south and lower-west spots have had far less time
⬥ **Long unattended runs** — a few hours, then tell me if the overlay numbers still look right (XP/hr, GP/hr, nuggets, ore totals)
⬥ **Busy worlds** — vein contention, and the water-wheel etiquette when someone else is repairing
⬥ **Deposit All + gem bag** — the slot lock is the fiddliest part of the whole thing
⬥ **The Imcando hammer path** if you own one
⬥ **Upstairs hopper** if you've unlocked it
⬥ **Mouse Activity + AFK Park Side** — does it feel right to you? AFK / Balanced / Active, and the park side has a `None` option if you don't want the cursor leaving the client

**Known limitations**

⬥ It doesn't spend nuggets — sack and upstairs upgrades are left to you
⬥ The overlay's nugget figure is what you've gained **this session**, not your total owned
⬥ The footer timer is **not** a "time to next vein" countdown. MLM veins collapse on a random per-ore roll, so no such countdown exists. While mining it counts up since your last XP drop — if it goes amber, the bot is probably walking or stuck, which is useful to screenshot

**Reporting a bug**

Reply in this thread with:
⬥ Which mining area, and your config (deposit method, gem bag on/off)
⬥ What it did vs what you expected
⬥ A screenshot with the overlay visible if you can — the status line and phase timer tell me a lot
⬥ Client logs if you have them

Cheers 🙏
