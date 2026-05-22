package net.runelite.client.plugins.microbot.irkedSlayer.task;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.api.npc.models.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;

import javax.inject.Singleton;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

@Singleton
public class IrkedSlayerTargetSelector {

    public boolean isBeingAttacked(int maxDistance) {
        return Microbot.getRs2NpcCache().query()
                .within(maxDistance)
                .where(npc -> !npc.isDead() && npc.isInteractingWithPlayer())
                .first() != null;
    }

    public Rs2NpcModel findNpcAttackingUs(
            List<String> targetMonsters,
            BiPredicate<String, String> nameMatcher,
            WorldPoint taskDestination,
            int attackRadius
    ) {
        var query = Microbot.getRs2NpcCache().query()
                .where(npc -> !npc.isDead() && npc.isInteractingWithPlayer())
                .where(npc -> npc.getName() != null)
                .where(npc -> targetMonsters.stream().anyMatch(monster -> nameMatcher.test(npc.getName(), monster)));

        if (taskDestination != null) {
            query = query.within(taskDestination, attackRadius + 5);
        } else {
            query = query.within(attackRadius + 5);
        }
        return query.nearest();
    }

    public Rs2NpcModel findAnyNpcAttackingUs(WorldPoint taskDestination, int attackRadius) {
        var query = Microbot.getRs2NpcCache().query()
                .where(npc -> npc.getName() != null)
                .where(npc -> !npc.isDead() && npc.isInteractingWithPlayer());

        if (taskDestination != null) {
            query = query.within(taskDestination, attackRadius + 5);
        } else {
            query = query.within(attackRadius + 5);
        }
        return query.nearest();
    }

    public Rs2NpcModel findNearbySuperior(Set<String> superiorMonsters, WorldPoint taskDestination, int attackRadius) {
        WorldPoint playerLocation = Rs2Player.getWorldLocation();
        if (playerLocation == null) {
            return null;
        }

        var query = Microbot.getRs2NpcCache().query()
                .where(npc -> !npc.isDead())
                .where(npc -> npc.getName() != null)
                .where(npc -> superiorMonsters.contains(npc.getName()));

        if (taskDestination != null) {
            query = query.within(taskDestination, attackRadius + 5);
        } else {
            query = query.within(attackRadius + 5);
        }

        return query.toList().stream()
                .min(Comparator.comparingInt(npc -> playerLocation.distanceTo(npc.getWorldLocation())))
                .orElse(null);
    }

    public List<Rs2NpcModel> findAttackableTargets(
            List<String> targetMonsters,
            BiPredicate<String, String> nameMatcher,
            WorldPoint taskDestination,
            int attackRadius
    ) {
        WorldPoint playerLocation = Rs2Player.getWorldLocation();
        if (playerLocation == null) {
            return java.util.Collections.emptyList();
        }

        var query = Microbot.getRs2NpcCache().query()
                .where(npc -> !npc.isDead())
                .where(npc -> npc.getName() != null)
                .where(npc -> targetMonsters.stream().anyMatch(monster -> nameMatcher.test(npc.getName(), monster)));

        if (taskDestination != null) {
            query = query.within(taskDestination, attackRadius);
        } else {
            query = query.within(attackRadius);
        }

        return query.toList().stream()
                .sorted(Comparator
                        .comparingInt((Rs2NpcModel npc) -> npc.isInteractingWithPlayer() ? 0 : 1)
                        .thenComparingInt(npc -> playerLocation.distanceTo(npc.getWorldLocation())))
                .collect(Collectors.toList());
    }

    public List<String> listNearbyAttackableNpcNames(WorldPoint taskDestination, int attackRadius) {
        var query = Microbot.getRs2NpcCache().query()
                .where(npc -> !npc.isDead())
                .where(npc -> npc.getName() != null);

        if (taskDestination != null) {
            query = query.within(taskDestination, attackRadius);
        } else {
            query = query.within(attackRadius);
        }

        return query.toList().stream()
                .map(Rs2NpcModel::getName)
                .distinct()
                .collect(Collectors.toList());
    }
}
