from pathlib import Path

p = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub\src\main\java\net\runelite\client\plugins\microbot\motherloadmine\session\MiningSession.java")
text = p.read_text(encoding="utf-8")

old = (
    "    /**\n"
    "     * Walk inside MLM without relying on web-walker in lower tunnels (pathfinder often null underground).\n"
    "     * Clears stale web-walk targets when already at the goal (avoids pathfinder-still-null stall).\n"
    "     */\n"
    "    /**\n"
)
new = "    /**\n"

if old in text:
    text = text.replace(old, new, 1)
elif "Walk inside MLM without relying" in text:
    raise SystemExit("block format mismatch")

if "Downstairs spots never trigger" not in text:
    text = text.replace(
        "    private boolean isWrongFloor(MLMMiningSpot spot) {",
        "    /**\n"
        "     * Returns {@code true} if the player must use the ladder before mining this spot.\n"
        "     * Downstairs spots never trigger a climb-up while in lower tunnels or at the spot.\n"
        "     */\n"
        "    private boolean isWrongFloor(MLMMiningSpot spot) {",
        1,
    )

p.write_text(text, encoding="utf-8")
print("OK", "Walk inside" in text)