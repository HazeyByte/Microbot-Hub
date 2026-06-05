package net.runelite.client.plugins.microbot.irkedmlm;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.agentserver.handler.ScriptResultStore;
import java.util.Map;

public class ProbeScript extends Script {
    @Override
    public boolean run() {
        ScriptResultStore.submit("ProbeScript", Map.of(
            "status", IrkedMLMScript.status.name(),
            "oreVein", IrkedMLMScript.oreVein != null ? IrkedMLMScript.oreVein.getWorldLocation().toString() : "null",
            "miningSpot", IrkedMLMScript.miningSpot.name()
        ));
        return false;
    }
}
