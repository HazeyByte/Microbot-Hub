package net.runelite.client;

import net.runelite.client.plugins.microbot.irkedmlm.enums.DepositMethod;
import static net.runelite.client.plugins.microbot.irkedmlm.session.SackSession.depositAllSafe;

/** Self-check for the Deposit-All gem-bag safety predicate. Run via the repo's JavaExec self-check path; no JUnit. */
public class IrkedMlmDepositSafetyCheck {
    static int fails = 0;
    static void check(boolean cond, String msg) { if (!cond) { fails++; System.out.println("FAIL: " + msg); } }

    public static void main(String[] args) {
        // params: useGemBag, method, bagInInventory, bagInSlot0, slot0Locked
        check(depositAllSafe(false, DepositMethod.ALL, true, false, false), "no gem bag feature -> safe");
        check(depositAllSafe(true, DepositMethod.ALL, false, false, false), "bag not in inventory (already banked) -> safe");
        check(!depositAllSafe(true, DepositMethod.ALL, true, false, false), "bag in inv, not in slot0 -> unsafe");
        check(!depositAllSafe(true, DepositMethod.ALL, true, true, false), "bag in slot0 but unlocked -> unsafe");
        check(depositAllSafe(true, DepositMethod.ALL, true, true, true), "bag in slot0 + locked -> safe");
        check(depositAllSafe(true, DepositMethod.ITEMS, true, false, false), "ITEMS -> safe");
        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILURES"));
        if (fails > 0) System.exit(1);
    }
}
