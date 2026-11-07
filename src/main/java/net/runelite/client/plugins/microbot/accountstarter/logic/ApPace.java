package net.runelite.client.plugins.microbot.accountstarter.logic;

import java.util.Random;

public final class ApPace {
    static final int REACTION_FLOOR_MS = 140;
    static final int READ_CAP_MS = 2_000;
    private static final double LAPSE_CHANCE = 0.02;

    private final double reactionMedian;
    private final double msPerChar;

    private ApPace(double reactionMedian, double msPerChar) {
        this.reactionMedian = reactionMedian;
        this.msPerChar = msPerChar;
    }

    public static ApPace draw(long seed) {
        Random r = new Random(seed);
        return new ApPace(250 + r.nextDouble() * 60, 6 + r.nextDouble() * 6);
    }

    public ApPace forSession(Random r) {
        return new ApPace(reactionMedian * (0.90 + r.nextDouble() * 0.25), msPerChar);
    }

    public long reactionMs(Random r) {
        long ms = Math.max(REACTION_FLOOR_MS, Math.round(reactionMedian * Math.exp(0.28 * r.nextGaussian())));
        return r.nextDouble() < LAPSE_CHANCE ? ms + 600 + r.nextInt(2401) : ms;
    }

    public long levelUpMs(Random r, double glanceChance) {
        return reactionMs(r) + (r.nextDouble() < glanceChance ? 1000 + r.nextInt(3001) : 0);
    }

    public long readMs(String text, Random r) {
        int chars = text == null ? 0 : text.replaceAll("<[^>]*>", "").trim().length();
        double read = chars * msPerChar * Math.exp(0.20 * r.nextGaussian());
        return Math.min(READ_CAP_MS, reactionMs(r) + Math.round(read));
    }
}
