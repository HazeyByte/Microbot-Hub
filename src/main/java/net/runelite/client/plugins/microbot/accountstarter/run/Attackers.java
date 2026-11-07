package net.runelite.client.plugins.microbot.accountstarter.run;

import java.util.Random;
import java.util.Arrays;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.accountstarter.logic.ApPace;
import net.runelite.client.plugins.microbot.util.Global;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.tile.Rs2Tile;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

final class Attackers {
    static final int ESCAPE_TILES = 10;
    private static final int ESCAPE_EXTRA = 3;

    private Attackers() {}

    /** Attack-option NPCs only: random-event NPCs also face us, and walking away just drags them along. */
    static Rs2NpcModel onUs(int except) {
        Player me = Microbot.getClient().getLocalPlayer();
        return Microbot.getRs2NpcCache().query()
                .where(n -> n.getIndex() != except && n.getInteracting() == me && !n.isDead() && n.getHealthRatio() != 0
                        && attackable(n))
                .nearestOnClientThread();
    }

    private static boolean attackable(Rs2NpcModel n) {
        NPCComposition c = n.getNpc() == null ? null : n.getNpc().getTransformedComposition();
        return c != null && c.getActions() != null && Arrays.asList(c.getActions()).contains("Attack");
    }

    static boolean escape(Rs2NpcModel from, ApPace pace, Random random) {
        WorldPoint me = Rs2Player.getWorldLocation();
        WorldPoint them = from.getWorldLocation();
        if (me == null) return false;
        int dx = them == null ? 0 : Integer.signum(me.getX() - them.getX());
        int dy = them == null ? 0 : Integer.signum(me.getY() - them.getY());
        if (dx == 0 && dy == 0) dx = random.nextBoolean() ? 1 : -1;
        int[][] dirs = {{dx, dy}, {dx, 0}, {0, dy}, {-dy, dx}, {dy, -dx}};
        int far = ESCAPE_TILES + random.nextInt(ESCAPE_EXTRA + 1);
        for (int[] d : dirs) {
            if (d[0] == 0 && d[1] == 0) continue;
            for (int k = far; k >= ESCAPE_TILES; k--) {
                WorldPoint p = me.dx(d[0] * k).dy(d[1] * k);
                if (!Rs2Tile.isWalkable(p)) continue;
                Global.sleep((int) pace.reactionMs(random));
                return Rs2Walker.walkTo(p, 1);
            }
        }
        return false;
    }
}
