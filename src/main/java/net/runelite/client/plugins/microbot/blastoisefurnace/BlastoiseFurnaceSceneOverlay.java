package net.runelite.client.plugins.microbot.blastoisefurnace;

import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.blastoisefurnace.enums.Bars;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;

import static net.runelite.api.gameval.ObjectID.BLAST_FURNACE_AUTOMATA_COFFER;
import static net.runelite.api.gameval.ObjectID.BLAST_FURNACE_CONVEYER_BELT_CLICKABLE;
import static net.runelite.api.gameval.VarbitID.BLAST_FURNACE_COAL;
import static net.runelite.api.gameval.VarbitID.BLAST_FURNACE_COFFER;

/**
 * World-anchored overlay: draws the live coffer balance over the coffer and the ore currently
 * on the belt over the conveyor, so the info sits where the player is looking rather than only
 * in the corner panel.
 */
public class BlastoiseFurnaceSceneOverlay extends Overlay {
    private final Client client;
    private final BlastoiseFurnaceConfig config;

    @Inject
    BlastoiseFurnaceSceneOverlay(Client client, BlastoiseFurnaceConfig config) {
        this.client = client;
        this.config = config;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D graphics) {
        try {
            int coffer = Microbot.getVarbitValue(BLAST_FURNACE_COFFER);
            drawObjectText(graphics, BLAST_FURNACE_AUTOMATA_COFFER, "Coffer: " + String.format("%,d", coffer), Color.YELLOW);

            drawObjectText(graphics, BLAST_FURNACE_CONVEYER_BELT_CLICKABLE, "On belt: " + oreOnBelt(), Color.CYAN);
        } catch (Exception ex) {
            Microbot.logStackTrace(getClass().getSimpleName(), ex);
        }
        return null;
    }

    /** Ore currently sitting in the furnace for the selected bar: primary ore + coal. */
    private int oreOnBelt() {
        Bars bar = config.getBars();
        int primary = Microbot.getVarbitValue(bar.getBFPrimaryOreID());
        int coal = Microbot.getVarbitValue(BLAST_FURNACE_COAL);
        // Secondary is coal for most bars (already counted); gold has no secondary furnace varbit.
        return primary + coal;
    }

    private void drawObjectText(Graphics2D graphics, int objectId, String text, Color color) {
        GameObject object = Rs2GameObject.getGameObject(objectId);
        if (object == null) {
            return;
        }
        Point loc = Perspective.getCanvasTextLocation(client, graphics, object.getLocalLocation(), text, 0);
        if (loc != null) {
            OverlayUtil.renderTextLocation(graphics, loc, text, color);
        }
    }
}
