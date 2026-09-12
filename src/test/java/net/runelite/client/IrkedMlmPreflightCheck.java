package net.runelite.client;

import net.runelite.client.plugins.microbot.irkedmlm.MlmPreflightLogic;
import net.runelite.client.plugins.microbot.irkedmlm.MlmPreflightLogic.HammerStep;
import net.runelite.client.plugins.microbot.irkedmlm.enums.DepositMethod;

/** Self-check for the pure preflight decision logic. Run via java; no JUnit. */
public class IrkedMlmPreflightCheck {
    static int fails = 0;
    static void check(boolean cond, String msg) { if (!cond) { fails++; System.out.println("FAIL: " + msg); } }

    public static void main(String[] args) {
        // nextHammerStep routing (first match wins; any equipped variant is DONE — equipment survives Deposit-All)
        check(MlmPreflightLogic.nextHammerStep(true, false, false, false, false) == HammerStep.DONE, "offhand equipped -> DONE");
        check(MlmPreflightLogic.nextHammerStep(false, true, false, false, false) == HammerStep.DONE, "mainhand equipped -> DONE");
        check(MlmPreflightLogic.nextHammerStep(false, false, true, false, false) == HammerStep.WIELD_OFFHAND, "offhand in inv -> WIELD_OFFHAND");
        check(MlmPreflightLogic.nextHammerStep(false, false, false, true, false) == HammerStep.SWAP_THEN_WIELD, "mainhand in inv -> SWAP_THEN_WIELD");
        check(MlmPreflightLogic.nextHammerStep(false, false, false, false, true) == HammerStep.WITHDRAW, "in bank -> WITHDRAW");
        check(MlmPreflightLogic.nextHammerStep(false, false, false, false, false) == HammerStep.FALLBACK_CRATE, "nothing -> FALLBACK_CRATE");
        // equipped wins even with inventory copies present
        check(MlmPreflightLogic.nextHammerStep(false, true, true, true, true) == HammerStep.DONE, "equipped beats inventory copies");
        // offhand-in-inv beats mainhand-in-inv
        check(MlmPreflightLogic.nextHammerStep(false, false, true, true, true) == HammerStep.WIELD_OFFHAND, "offhand-inv precedence over mainhand-inv");

        // resolveEffectiveDepositMethod (configured, useGemBag, lockEstablished)
        check(MlmPreflightLogic.resolveEffectiveDepositMethod(DepositMethod.ITEMS, true, true) == DepositMethod.ITEMS, "ITEMS stays ITEMS");
        check(MlmPreflightLogic.resolveEffectiveDepositMethod(DepositMethod.ALL, false, false) == DepositMethod.ALL, "ALL + no gembag -> ALL");
        check(MlmPreflightLogic.resolveEffectiveDepositMethod(DepositMethod.ALL, true, true) == DepositMethod.ALL, "ALL + gembag + locked -> ALL");
        check(MlmPreflightLogic.resolveEffectiveDepositMethod(DepositMethod.ALL, true, false) == DepositMethod.ITEMS, "ALL + gembag + not locked -> ITEMS");

        System.out.println(fails == 0 ? "ALL PASS" : (fails + " FAILURES"));
        if (fails > 0) System.exit(1);
    }
}
