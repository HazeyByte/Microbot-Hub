from pathlib import Path
import re

p = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub\src\main\java\net\runelite\client\plugins\microbot\motherloadmine\MotherloadMineScript.java")
text = p.read_text(encoding="utf-8")

old_block = """    /**
     * Minimum wall-clock time (ms) that must elapse before we allow to begin() to be
     * called again on the hopper or sack sessions.
     */
    private static final long LADDER_INTERACTION_COOLDOWN_MS = 5_000L;

"""
if old_block not in text:
    raise SystemExit("block1 not found")
text = text.replace(old_block, "")

old_javadoc = """    /**
     * Timestamp of the last time we called begin() on either the hopper or sack
     * session. Used to enforce {@link #LADDER_INTERACTION_COOLDOWN_MS}.
     */
    private long lastLadderInteractionMs = 0L;"""
new_javadoc = """    /** Timestamp of the last hopper/sack begin() (ladder throttle). */
    private long lastLadderInteractionMs = 0L;"""
if old_javadoc not in text:
    raise SystemExit("block2 not found")
text = text.replace(old_javadoc, new_javadoc)

m = re.search(r"    private boolean canInteractWithLadder\(\) \{.*?\n    \}", text, re.DOTALL)
if not m:
    raise SystemExit("method not found")

new_method = """    /**
     * Wall-clock throttle between hopper/sack begin() calls (prevents ladder spam on the
     * 600ms executor). Jittered per check; shorter when human-like is off.
     */
    private long ladderInteractionCooldownMs() {
        if (isHumanLikeEnabled()) {
            return Rs2Random.between(1_500, 2_600);
        }
        return Rs2Random.between(800, 1_300);
    }

    private boolean canInteractWithLadder() {
        if (lastLadderInteractionMs == 0L) {
            return true;
        }
        long elapsed = System.currentTimeMillis() - lastLadderInteractionMs;
        long required = ladderInteractionCooldownMs();
        boolean ready = elapsed >= required;
        if (!ready && (status == MLMStatus.EMPTY_SACK || status == MLMStatus.DEPOSIT_HOPPER)) {
            debug("[MLM] Ladder cooldown active for {} — {}ms left", status, required - elapsed);
        }
        return ready;
    }"""
text = text[: m.start()] + new_method + text[m.end() :]

text = text.replace(
    "at a distant mining spot for the full 5s. The session will take over when ready.",
    "at a distant mining spot while the ladder throttle elapses.",
)

p.write_text(text, encoding="utf-8", newline="\n")
print("OK")