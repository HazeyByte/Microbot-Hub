package net.runelite.client.plugins.microbot.irkedSlayer.task;

import net.runelite.client.plugins.microbot.irkedSlayer.profile.IrkedSlayerTaskProfileJson;
import net.runelite.client.plugins.microbot.util.skills.slayer.Rs2Slayer;

import javax.inject.Singleton;
import java.util.Arrays;
import java.util.List;

@Singleton
public class IrkedSlayerTaskResolver {

    public List<String> getTargetMonsterNames(IrkedSlayerTaskProfileJson activeJsonProfile) {
        if (activeJsonProfile != null && activeJsonProfile.hasVariant()) {
            return Arrays.asList(activeJsonProfile.getVariant());
        }

        List<String> monsters = Rs2Slayer.getSlayerMonsters();
        if (monsters != null && !monsters.isEmpty()) {
            return monsters;
        }

        String taskName = Rs2Slayer.getSlayerTask();
        if (taskName != null && !taskName.isEmpty()) {
            return Arrays.asList(convertTaskNameToNpcName(taskName));
        }

        return null;
    }

    public boolean isUsingVariant(IrkedSlayerTaskProfileJson activeJsonProfile) {
        return activeJsonProfile != null && activeJsonProfile.hasVariant();
    }

    public boolean matchesTargetMonster(String npcName, String targetMonster, boolean usingVariant) {
        if (npcName == null || targetMonster == null) {
            return false;
        }

        String npcLower = npcName.toLowerCase();
        String targetLower = targetMonster.toLowerCase();
        if (usingVariant) {
            return npcLower.equals(targetLower);
        }

        return npcLower.contains(targetLower) || targetLower.contains(npcLower);
    }

    public String convertTaskNameToNpcName(String taskName) {
        if (taskName == null || taskName.isEmpty()) {
            return taskName;
        }

        String result = taskName.trim();
        if (result.endsWith("s") && !result.endsWith("ss")) {
            result = result.substring(0, result.length() - 1);
        }

        String[] words = result.split(" ");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (words[i].isEmpty()) {
                continue;
            }
            if (i == 0) {
                sb.append(Character.toUpperCase(words[i].charAt(0)));
                if (words[i].length() > 1) {
                    sb.append(words[i].substring(1).toLowerCase());
                }
            } else {
                sb.append(" ").append(words[i].toLowerCase());
            }
        }
        return sb.toString();
    }
}
