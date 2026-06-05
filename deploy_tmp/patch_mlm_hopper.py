from pathlib import Path

hopper_path = Path(r"src/main/java/net/runelite/client/plugins/microbot/motherloadmine/session/HopperSession.java")
script_path = Path(r"src/main/java/net/runelite/client/plugins/microbot/motherloadmine/MotherloadMineScript.java")
root = Path(r"C:\Users\imatt\IdeaProjects\Microbot-Hub")

hopper = (root / hopper_path).read_text(encoding="utf-8")

if "depositMiningSpot" not in hopper:
    hopper = hopper.replace(
        "    @Getter\n    private int depositRetryCount = 0;\n\n    private final Rs2TileObjectCache tileCache;",
        "    @Getter\n    private int depositRetryCount = 0;\n\n"
        "    /** Active mining area; downstairs spots always use the lower hopper. */\n"
        "    private MLMMiningSpot depositMiningSpot;\n\n"
        "    private final Rs2TileObjectCache tileCache;",
    )
    hopper = hopper.replace(
        "        depositRetryCount    = 0;\n    }",
        "        depositRetryCount    = 0;\n        depositMiningSpot    = null;\n    }",
    )
    hopper = hopper.replace(
        "    @Override\n    public void begin() {",
        "    public void begin(MLMMiningSpot miningSpot) {\n"
        "        this.depositMiningSpot = miningSpot;\n"
        "        begin();\n"
        "    }\n\n"
        "    @Override\n    public void begin() {",
    )

old_helpers = """    /**
     * Returns true if the player's current floor does not match the desired hopper floor.
     * Desired = upper if config.upstairsHopperUnlocked(), else lower (always).
     */
    private boolean needsFloorTransition() {
        boolean onUpper = isUpperFloor();
        boolean wantUpper = config.upstairsHopperUnlocked();
        return onUpper != wantUpper;
    }

    /** The floor the hopper session intends to deposit on (based on config). */
    private boolean desiredFloorIsUpper() {
        return config.upstairsHopperUnlocked();
    }

    /**
     * Public helper so the orchestrating script can peek (e.g. for logging or pre-begin checks)
     * without duplicating the desired-floor math.
     */
    public boolean isOnCorrectHopperFloor() {
        return isUpperFloor() == config.upstairsHopperUnlocked();
    }"""

new_helpers = """    /**
     * Returns true if the player's current floor does not match the desired hopper floor.
     * Downstairs mining spots always target the lower hopper regardless of config.
     */
    private boolean needsFloorTransition() {
        return isUpperFloor() != desiredFloorIsUpper();
    }

    /** Upper hopper only when config allows and the active mining area is upstairs. */
    private boolean desiredFloorIsUpper() {
        if (depositMiningSpot != null && depositMiningSpot.isDownstairs()) {
            return false;
        }
        return config.upstairsHopperUnlocked();
    }

    /**
     * Public helper so the orchestrating script can peek (e.g. for logging or pre-begin checks)
     * without duplicating the desired-floor math.
     */
    public boolean isOnCorrectHopperFloor() {
        return isUpperFloor() == desiredFloorIsUpper();
    }"""

if old_helpers not in hopper:
    raise SystemExit("HopperSession helpers block not found")
hopper = hopper.replace(old_helpers, new_helpers)
(root / hopper_path).write_text(hopper, encoding="utf-8")
print("HopperSession OK")

s = (root / script_path).read_text(encoding="utf-8")

if "useUpperHopperForDeposit" not in s:
    s = s.replace(
        """    private boolean isSackFull() {
        if (sackIsFullFlag) return true;
        return maxSackSize > 0 && currentSackCount() >= maxSackSize;
    }

    // =========================================================================
    // Post-deposit audit""",
        """    private boolean isSackFull() {
        if (sackIsFullFlag) return true;
        return maxSackSize > 0 && currentSackCount() >= maxSackSize;
    }

    /** Upper hopper config applies only when the active mining area is upstairs. */
    private boolean useUpperHopperForDeposit() {
        return config.upstairsHopperUnlocked()
                && miningSpot != null
                && miningSpot.isUpstairs();
    }

    private WorldPoint hopperWalkTarget() {
        return useUpperHopperForDeposit()
                ? new WorldPoint(3755, 5677, 0)
                : new WorldPoint(3748, 5672, 0);
    }

    /** Hopper deposit is only warranted when the inventory is full of pay-dirt. */
    private boolean shouldDepositPayDirtAtHopper() {
        return Rs2Inventory.isFull() && payDirtCount() > 0;
    }

    // =========================================================================
    // Post-deposit audit""",
    )

s = s.replace(
    """        if (payDirtCount() > 0) {
            if (sackFull) {
                log.info("[MLM] Post-repair: pay-dirt remains but sack is full — dropping pay-dirt then EMPTY_SACK");
                dropAllPayDirt();
                return MLMStatus.EMPTY_SACK;
            }
            return MLMStatus.DEPOSIT_HOPPER;
        }""",
    """        if (Rs2Inventory.isFull() && payDirtCount() > 0) {
            if (sackFull) {
                log.info("[MLM] Post-repair: pay-dirt remains but sack is full — dropping pay-dirt then EMPTY_SACK");
                dropAllPayDirt();
                return MLMStatus.EMPTY_SACK;
            }
            return MLMStatus.DEPOSIT_HOPPER;
        }""",
)

s = s.replace(
    """            } else {
                log.info("[MLM] Pay-dirt ({}) in inventory during EMPTY_SACK — depositing at hopper first",
                        payDirtCount());
                status = MLMStatus.DEPOSIT_HOPPER;
                return;
            }""",
    """            } else if (Rs2Inventory.isFull()) {
                log.info("[MLM] Full inventory of pay-dirt ({}) during EMPTY_SACK — depositing at hopper first",
                        payDirtCount());
                status = MLMStatus.DEPOSIT_HOPPER;
                return;
            } else {
                log.debug("[MLM] Partial pay-dirt ({}) during EMPTY_SACK — continuing to empty sack (no hopper)",
                        payDirtCount());
            }""",
)

if "Aborting hopper deposit" not in s:
    s = s.replace(
        """    private void handleDepositHopperStatus() {
        // If sack is full""",
        """    private void handleDepositHopperStatus() {
        if (!shouldDepositPayDirtAtHopper()) {
            log.info("[MLM] Aborting hopper deposit — inventory not full (pay-dirt={})", payDirtCount());
            hopperSession.reset();
            status = MLMStatus.MINING;
            return;
        }

        // If sack is full""",
    )

s = s.replace("            hopperSession.begin();", "            hopperSession.begin(miningSpot);")

s = s.replace(
    """            WorldPoint hopperTarget = config.upstairsHopperUnlocked()
                    ? new WorldPoint(3755, 5677, 0)
                    : new WorldPoint(3748, 5672, 0);""",
    "            WorldPoint hopperTarget = hopperWalkTarget();",
)

s = s.replace(
    """                    debug("[MLM] HopperSession: ladder complete, now on correct floor for hopper (upper={})",
                            config.upstairsHopperUnlocked());""",
    """                    debug("[MLM] HopperSession: ladder complete, now on correct floor for hopper (upper={})",
                            useUpperHopperForDeposit());""",
)

s = s.replace(
    """        if (attempts == 1 && lastHopperDepositTimestampMs > 0
                && System.currentTimeMillis() - lastHopperDepositTimestampMs < 15000L) {
            debug("[MLM] Recovery: attempting hopper re-click before full recovery");
            hopperSession.reset();
            status = MLMStatus.DEPOSIT_HOPPER;
            return;
        }""",
    """        if (attempts == 1 && shouldDepositPayDirtAtHopper()
                && lastHopperDepositTimestampMs > 0
                && System.currentTimeMillis() - lastHopperDepositTimestampMs < 15000L) {
            debug("[MLM] Recovery: attempting hopper re-click before full recovery (inv full)");
            hopperSession.reset();
            status = MLMStatus.DEPOSIT_HOPPER;
            return;
        }""",
)

s = s.replace(
    """            log.info("[MLM] Audit: deposit failed ({} / {} remain) — retrying ({}/{})",
                    residual, initial, hopperSession.getDepositRetryCount(), HopperSession.MAX_DEPOSIT_RETRIES);
            status = MLMStatus.DEPOSIT_HOPPER;
            return;""",
    """            if (!shouldDepositPayDirtAtHopper()) {
                log.info("[MLM] Audit: deposit failed but inv not full ({} remain) — resuming MINING", residual);
                hopperSession.reset();
                status = MLMStatus.MINING;
                return;
            }
            log.info("[MLM] Audit: deposit failed ({} / {} remain) — retrying ({}/{})",
                    residual, initial, hopperSession.getDepositRetryCount(), HopperSession.MAX_DEPOSIT_RETRIES);
            status = MLMStatus.DEPOSIT_HOPPER;
            return;""",
)

s = s.replace(
    """        log.info("[MLM] Audit: partial deposit ({} / {} remain) — retrying ({}/{})",
                residual, initial, hopperSession.getDepositRetryCount(), HopperSession.MAX_DEPOSIT_RETRIES);
        status = MLMStatus.DEPOSIT_HOPPER;""",
    """        if (!shouldDepositPayDirtAtHopper()) {
            log.info("[MLM] Audit: partial deposit ({} remain) but inv not full — resuming MINING", residual);
            hopperSession.reset();
            status = MLMStatus.MINING;
            return;
        }
        log.info("[MLM] Audit: partial deposit ({} / {} remain) — retrying ({}/{})",
                residual, initial, hopperSession.getDepositRetryCount(), HopperSession.MAX_DEPOSIT_RETRIES);
        status = MLMStatus.DEPOSIT_HOPPER;""",
)

(root / script_path).write_text(s, encoding="utf-8")
print("MotherloadMineScript OK")