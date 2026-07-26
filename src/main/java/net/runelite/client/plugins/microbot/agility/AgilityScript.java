package net.runelite.client.plugins.microbot.agility;

import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.agentserver.handler.ScriptHeartbeatRegistry;
import net.runelite.client.plugins.microbot.agility.courses.AgilityCourseHandler;
import net.runelite.client.plugins.microbot.agility.enums.AgilityCourse;
import net.runelite.client.plugins.microbot.agility.models.AgilityObstacleModel;
import net.runelite.client.plugins.microbot.api.tileitem.models.Rs2TileItemModel;
import net.runelite.client.plugins.microbot.util.antiban.Rs2Antiban;
import net.runelite.client.plugins.microbot.util.antiban.Rs2AntibanSettings;
import net.runelite.client.plugins.microbot.util.camera.Rs2Camera;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.inventory.Rs2Inventory;
import net.runelite.client.plugins.microbot.util.magic.Rs2Magic;
import net.runelite.client.plugins.microbot.util.menu.NewMenuEntry;
import net.runelite.client.plugins.microbot.util.misc.Rs2UiHelper;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.reflection.Rs2Reflection;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;

import javax.inject.Inject;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.EventQueue;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class AgilityScript extends Script
{

	final MicroAgilityPlugin plugin;
	final MicroAgilityConfig config;

	WorldPoint startPoint = null;
	int lastAgilityXp = 0;
	long lastTimeoutWarning = 0;  // For throttled timeout warnings
	private AgilityCourse activeCourse = null;
	private AgilityCourseHandler activeHandler = null;
	private static final long MAIN_LOOP_DELAY_MS = 250;
	// If the client thread stops responding (e.g. the game engine itself has wedged - not
	// something this script can fix), Microbot.getClientThread().invoke(...) throws a
	// "Timed out waiting for client thread" RuntimeException on every tick. Without a limit the
	// script retries forever, spamming the log while the client is already unresponsive. After
	// this many consecutive timeouts (~5 x up to 10s each = a genuinely dead client thread, not a
	// momentary blip), stop cleanly instead of hammering it.
	private static final int CLIENT_THREAD_TIMEOUT_STOP_THRESHOLD = 5;
	private int consecutiveClientThreadTimeouts = 0;
	// waitForCompletion() returns false only when we clicked an obstacle but got no XP, no health
	// drop, and no plane change for its full timeout - i.e. genuinely wedged (obstacle unreachable,
	// stuck on a bad tile, or an off-screen target walkMiniMap never brought on-screen). Banking,
	// eating and mark pickup all return earlier in the loop and never reach that click, so counting
	// consecutive timeouts here is a false-positive-free stuck signal. After this many in a row,
	// re-navigate to the current obstacle to break out instead of clicking the same dead spot.
	private static final int OBSTACLE_TIMEOUT_STUCK_THRESHOLD = 3;
	private int consecutiveObstacleTimeouts = 0;
	// Human-like hover that keeps the cursor ON the next obstacle and follows it as the camera pans
	// (see startHoverTracking()/trackHover()).
	private static final int HOVER_CHANCE_PERCENT = 92;      // pre-hover the next obstacle most of the time - a person occasionally just doesn't
	private static final long HOVER_CHECK_INTERVAL_MS = 150; // how often we check whether drift has moved the object off the cursor
	private static final double HOVER_COMFORT_MARGIN = 0.20; // rest while the cursor is within this inner fraction of the clickbox; only correct past it
	private static final double HOVER_TARGET_MARGIN = 0.33;  // corrections land in this smaller central region so the cursor re-centres with room to spare (smoother, fewer corrections)
	// Rare human input errors on the obstacle click (per-mille = out of 1000). Kept genuinely rare
	// so they read as occasional slip-ups, not a tic.
	private static final int MISCLICK_PER_MILLE = 5;         // very rare: one wasted click just off the object before clicking it properly
	private static final int MULTI_CLICK_PER_MILLE = 15;     // rare: a second redundant click on the same obstacle
	// When we genuinely can't locate an obstacle for this many consecutive ticks we're lost - webwalk
	// back to the course start (no teleport; Rs2Walker.disableTeleports stays true for the whole run).
	private static final int NO_OBSTACLE_LOST_THRESHOLD = 4;
	private int consecutiveNoObstacle = 0;
	// Dedicated daemon so the hover runs concurrently with waitForCompletion without touching it.
	// During the wait only this thread drives the mouse, so there is no contention.
	private final ScheduledExecutorService hoverExecutor =
		Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "AgilityScript-hover-tracker");
			t.setDaemon(true);
			return t;
		});
	private ScheduledFuture<?> hoverFuture;
	private static final long MARK_OF_GRACE_SCAN_INTERVAL_MS = 750;
	private static final int MARK_OF_GRACE_SEARCH_DISTANCE = 30;
	private static final int MARK_OF_GRACE_PICKUP_TIMEOUT = 5000;
	private volatile int currentObstacleIndex = -1;
	private WorldPoint pendingMarkOfGraceLocation = null;
	private int pendingMarkOfGraceCount = 0;
	private long pendingMarkOfGraceStartedAt = 0;
	private long lastMarkOfGraceScanAt = 0;
	private WorldPoint alchDecisionObstacleLocation = null;
	private int alchDecisionObstacleId = -1;
	private boolean alchDecisionShouldAlch = false;
	private final AgilitySupplyManager supplyManager;
	private volatile boolean shuttingDown = false;

	@Inject
	public AgilityScript(MicroAgilityPlugin plugin, MicroAgilityConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		this.supplyManager = new AgilitySupplyManager(plugin, config, this::isShuttingDown, this::shutdown);
	}

	@Override
	public void shutdown()
	{
		shuttingDown = true;
		consecutiveClientThreadTimeouts = 0;
		consecutiveObstacleTimeouts = 0;
		consecutiveNoObstacle = 0;
		stopHoverTracking();
		ScriptHeartbeatRegistry.remove(this.getClass().getName());
		if (activeHandler != null)
		{
			activeHandler.reset();
		}
		activeCourse = null;
		activeHandler = null;
		startPoint = null;
		initialPlayerLocation = null;
		currentObstacleIndex = -1;
		supplyManager.reset();
		clearPendingMarkOfGrace();
		clearAlchDecision();

		if (mainScheduledFuture != null && !mainScheduledFuture.isDone())
		{
			mainScheduledFuture.cancel(true);
		}
		if (scheduledFuture != null && !scheduledFuture.isDone())
		{
			scheduledFuture.cancel(true);
		}

		clearWalkingRouteForShutdown();
		Microbot.pauseAllScripts.set(false);
		Rs2Walker.disableTeleports = false;
		Microbot.getSpecialAttackConfigs().reset();
	}

	private void clearWalkingRouteForShutdown()
	{
		if (!EventQueue.isDispatchThread())
		{
			Rs2Walker.clearWalkingRoute("agility:shutdown");
			return;
		}

		Thread cleanupThread = new Thread(() -> Rs2Walker.clearWalkingRoute("agility:shutdown"), "AgilityScript-shutdown-cleanup");
		cleanupThread.setDaemon(true);
		cleanupThread.start();
	}

	public boolean run()
	{
		shuttingDown = false;
		Microbot.enableAutoRunOn = true;
		Rs2Antiban.resetAntibanSettings();
		Rs2Antiban.antibanSetupTemplates.applyAgilitySetup();
		// A same-course recovery walk should never require a teleport. Without this, the
		// walker's teleport-selection cost model can pick an irrational route (e.g. teleporting
		// to Lumbridge and walking back across the map) when a course's local geometry makes the
		// real walking path look expensive to its search. shutdown() resets this to false.
		Rs2Walker.disableTeleports = true;
		AgilityCourseHandler initialHandler = getActiveHandler();
		startPoint = initialHandler.getStartPoint();
		lastAgilityXp = Microbot.getClient().getSkillExperience(Skill.AGILITY);
		mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
			try
			{
				if (!Microbot.isLoggedIn())
				{
					return;
				}
				if (!super.run())
				{
					return;
				}
				AgilityCourseHandler courseHandler = getActiveHandler();
				final WorldPoint playerWorldLocation = Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getWorldLocation());
				consecutiveClientThreadTimeouts = 0;

				if (startPoint == null)
				{
					Microbot.log("Early return: Start point is null");
					Microbot.showMessage("Agility course: " + config.agilityCourse().getTooltip() + " is not supported.");
					sleep(10000);
					return;
				}

				boolean hasRequiredLevel = plugin.hasRequiredLevel(courseHandler);
				currentObstacleIndex = courseHandler.getCurrentObstacleIndex();
				AgilitySupplyManager.InventorySnapshot inventorySnapshot = supplyManager.createInventorySnapshot();
				if (supplyManager.handleSummerPies(courseHandler, playerWorldLocation, currentObstacleIndex, inventorySnapshot))
				{
					Microbot.log("Early return: Handling summer pies");
					return;
				}

				if (supplyManager.handleFoodOrHealthSafety(inventorySnapshot))
				{
					Microbot.log("Early return: Handling agility safety");
					return;
				}

				if (supplyManager.handlePreLevelCheck(courseHandler, playerWorldLocation, currentObstacleIndex, hasRequiredLevel, inventorySnapshot))
				{
					Microbot.log("Early return: Banking agility supplies");
					return;
				}

				if (supplyManager.handleBeforeObstacle(courseHandler, playerWorldLocation, currentObstacleIndex, inventorySnapshot))
				{
					Microbot.log("Early return: Handling agility supplies");
					return;
				}

				if (!hasRequiredLevel)
				{
					if (supplyManager.shouldWalkToCourseStartForSummerPie(courseHandler, playerWorldLocation, currentObstacleIndex)
						&& courseHandler.handleCourseActions(playerWorldLocation))
					{
						return;
					}
					Microbot.log("Early return: Required level not met");
					plugin.notifyUser("Your Agility level is too low for " + config.agilityCourse().getTooltip() + ". Select another course, or enable summer pies if a +5 boost is enough.");
					shutdown();
					return;
				}

				if (!courseHandler.hasRequiredCourseItems())
				{
					Microbot.log("Early return: Missing required course items");
					Microbot.showMessage(courseHandler.getMissingRequiredCourseItemsMessage());
					shutdown();
					return;
				}
				if (Rs2AntibanSettings.actionCooldownActive)
				{
					Microbot.log("Early return: Action cooldown active");
					return;
				}
				final int currentAgilityXp = Microbot.getClient().getSkillExperience(Skill.AGILITY);

				if (lootMarksOfGrace(courseHandler))
				{
					Microbot.log("Early return: Looting marks of grace");
					return;
				}

				if (courseHandler.handleCourseActions(playerWorldLocation))
				{
					return;
				}
				final int agilityExp = currentAgilityXp;

				TileObject gameObject = courseHandler.getCurrentObstacle();

				if (gameObject == null)
				{
					consecutiveNoObstacle++;
					if (consecutiveNoObstacle >= NO_OBSTACLE_LOST_THRESHOLD)
					{
						// Genuinely lost (not a one-tick blip) - webwalk back to the course start.
						// disableTeleports is on for the whole run, so this walks, never teleports.
						Microbot.log("Can't locate an obstacle - lost. Walking to course start (no teleport).");
						Rs2Walker.walkTo(startPoint, 2);
						consecutiveNoObstacle = 0;
					}
					else
					{
						Microbot.log("No agility obstacle found (" + consecutiveNoObstacle + "/" + NO_OBSTACLE_LOST_THRESHOLD + ") - retrying");
					}
					return;
				}
				consecutiveNoObstacle = 0;

				if (!Rs2Camera.isTileOnScreen(gameObject))
				{
					Rs2Walker.walkMiniMap(gameObject.getWorldLocation());
				}

				// Check if we should click (handles animation/XP logic)
				if (!courseHandler.shouldClickObstacle(currentAgilityXp, lastAgilityXp))
				{
					return; // Not ready to click yet
				}
				
				// Update XP if we got it while animating
				if (currentAgilityXp > lastAgilityXp)
				{
					lastAgilityXp = currentAgilityXp;
				}

				// Handle alchemy if enabled
				if (shouldPerformAlch(gameObject))
				{
					Optional<String> alchItem = getAlchItem();
					if (alchItem.isPresent())
					{
						// Check if we should skip inefficient alchs
						if (config.skipInefficient())
						{
							// Only alch if obstacle is far enough for efficient alching
							if (gameObject.getWorldLocation().distanceTo(playerWorldLocation) >= 5)
							{
								if (config.efficientAlching())
								{
									if (performEfficientAlch(gameObject, alchItem.get(), agilityExp))
									{
										return;
									}
								}
								else
								{
									// Still do normal alch if far enough but efficient alching is disabled
									if (performNormalAlch(alchItem.get()))
									{
										return;
									}
								}
							}
							// Skip alching if obstacle is too close
						}
						else
						{
							// Normal behavior when skipInefficient is disabled
							if (config.efficientAlching())
							{
								if (performEfficientAlch(gameObject, alchItem.get(), agilityExp))
								{
									return;
								}
							}
							// Fall back to normal alching
							if (performNormalAlch(alchItem.get()))
							{
								return;
							}
						}
					}
				}
				
				// Normal obstacle interaction (with rare human input errors)
				if (interactWithObstacleHumanized(gameObject)) {
					// While the current obstacle plays out, keep the cursor tracking the next one
					// (the way a human's hand drifts to the next platform mid-traversal) - see
					// startHoverTracking(). Runs concurrently with waitForCompletion below.
					TileObject nextObstacle = resolveNextObstacle(courseHandler, gameObject, currentObstacleIndex);
					startHoverTracking(nextObstacle);

					// Wait for completion - this now returns quickly on XP drop
					boolean completed;
					try {
						completed = courseHandler.waitForCompletion(agilityExp,
							Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getWorldLocation()).getPlane());
					} finally {
						stopHoverTracking();
					}

					if (!completed) {
						consecutiveObstacleTimeouts++;
						// Timeout occurred - log warning (throttled to once per 30 seconds)
						long now = System.currentTimeMillis();
						if (now - lastTimeoutWarning > 30000) {
							Microbot.log("Obstacle completion timed out (" + consecutiveObstacleTimeouts + "/" + OBSTACLE_TIMEOUT_STUCK_THRESHOLD + ") - retrying");
							lastTimeoutWarning = now;
						}
						if (consecutiveObstacleTimeouts >= OBSTACLE_TIMEOUT_STUCK_THRESHOLD) {
							// Wedged: re-navigate to the current obstacle to break the loop.
							// disableTeleports keeps this a local walk, not a cross-map teleport.
							Microbot.log("Obstacle stuck - re-walking to current obstacle to recover");
							Rs2Walker.walkTo(gameObject.getWorldLocation(), 2);
							consecutiveObstacleTimeouts = 0;
						}
						return;  // Bail early to avoid acting on stale state
					}
					consecutiveObstacleTimeouts = 0;

					// XP tracking is already updated before clicking (line 137)
					// Don't update here to avoid losing early action state
					clearAlchDecision();
					
					// If we're still animating after XP, don't add delays - proceed immediately
					if (!Rs2Player.isAnimating() && !Rs2Player.isMoving()) {
						// Only add delays if we're not animating
						Rs2Antiban.actionCooldown();
						Rs2Antiban.takeMicroBreakByChance();
					}
				}
			}
			catch (Exception ex)
			{
				if (isExpectedShutdownInterrupt(ex))
				{
					return;
				}
				if (isClientThreadTimeout(ex))
				{
					consecutiveClientThreadTimeouts++;
					Microbot.log("Client thread did not respond (" + consecutiveClientThreadTimeouts + "/" + CLIENT_THREAD_TIMEOUT_STOP_THRESHOLD + ")");
					if (consecutiveClientThreadTimeouts >= CLIENT_THREAD_TIMEOUT_STOP_THRESHOLD)
					{
						plugin.notifyUser("Client stopped responding - stopping Agility. Restart the client if this persists.");
						shutdown();
					}
					return;
				}
				Microbot.log("An error occurred: " + ex.getMessage(), ex);
			}
		}, 0, MAIN_LOOP_DELAY_MS, TimeUnit.MILLISECONDS);
		return true;
	}

	public boolean isShuttingDown()
	{
		return shuttingDown;
	}

	public int getCurrentObstacleIndex()
	{
		return currentObstacleIndex;
	}

	private boolean isExpectedShutdownInterrupt(Exception ex)
	{
		if (!shuttingDown)
		{
			return false;
		}

		if (Thread.currentThread().isInterrupted())
		{
			return true;
		}

		Throwable current = ex;
		while (current != null)
		{
			if (current instanceof InterruptedException)
			{
				return true;
			}
			if (current.getMessage() != null && current.getMessage().contains("Interrupted waiting for client thread"))
			{
				return true;
			}
			current = current.getCause();
		}

		return false;
	}

	private boolean isClientThreadTimeout(Exception ex)
	{
		Throwable current = ex;
		while (current != null)
		{
			if (current.getMessage() != null && current.getMessage().contains("Timed out waiting for client thread"))
			{
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private Optional<String> getAlchItem()
	{
		String itemsInput = config.itemsToAlch().trim();
		if (itemsInput.isEmpty())
		{
			// Microbot.log("No items specified for alching or none available.");
			return Optional.empty();
		}

		List<String> itemsToAlch = Arrays.stream(itemsInput.split(","))
			.map(String::trim)
			.map(String::toLowerCase)
			.filter(s -> !s.isEmpty())
			.collect(Collectors.toList());

		if (itemsToAlch.isEmpty())
		{
			// Microbot.log("No valid items specified for alching.");
			return Optional.empty();
		}

		for (String itemName : itemsToAlch)
		{
			if (Rs2Inventory.hasItem(itemName))
			{
				return Optional.of(itemName);
			}
		}

		return Optional.empty();
	}

	private AgilityCourseHandler getActiveHandler()
	{
		AgilityCourse selectedCourse = config.agilityCourse();
		if (activeHandler == null || activeCourse != selectedCourse)
		{
			if (activeHandler != null)
			{
				activeHandler.reset();
			}

			activeCourse = selectedCourse;
			activeHandler = selectedCourse.getHandler();
			activeHandler.reset();
			startPoint = activeHandler.getStartPoint();
			lastAgilityXp = Microbot.getClient().getSkillExperience(Skill.AGILITY);
			currentObstacleIndex = -1;
			supplyManager.reset();
			clearAlchDecision();
		}
		return activeHandler;
	}

	private boolean lootMarksOfGrace(AgilityCourseHandler courseHandler)
	{
		if (shuttingDown)
		{
			clearPendingMarkOfGrace();
			return false;
		}

		if (pendingMarkOfGraceLocation != null)
		{
			if (markPickupResolved() || System.currentTimeMillis() - pendingMarkOfGraceStartedAt > MARK_OF_GRACE_PICKUP_TIMEOUT)
			{
				clearPendingMarkOfGrace();
			}
			else if (Rs2Player.isMoving() || Rs2Player.isAnimating())
			{
				return true;
			}
			else
			{
				clearPendingMarkOfGrace();
			}
		}

		if (Rs2Inventory.isFull() && !Rs2Inventory.contains(ItemID.GRACE))
		{
			return false;
		}

		WorldPoint playerLocation = courseHandler.getPlayerWorldLocation();
		if (playerLocation == null)
		{
			return false;
		}

		long now = System.currentTimeMillis();
		if (now - lastMarkOfGraceScanAt < MARK_OF_GRACE_SCAN_INTERVAL_MS)
		{
			return false;
		}
		lastMarkOfGraceScanAt = now;

		Rs2TileItemModel markOfGrace = Microbot.getRs2TileItemCache().query()
			.fromWorldView()
			.withId(ItemID.GRACE)
			.where(Rs2TileItemModel::isLootAble)
			.where(item -> item.getWorldLocation() != null && item.getWorldLocation().getPlane() == playerLocation.getPlane())
			.where(item -> item.getWorldLocation().distanceTo(playerLocation) <= MARK_OF_GRACE_SEARCH_DISTANCE)
			.where(item -> Rs2Walker.canReach(item.getWorldLocation()))
			.toList()
			.stream()
			.min(Comparator.comparingInt(item -> item.getWorldLocation().distanceTo(playerLocation)))
			.orElse(null);

		if (markOfGrace == null)
		{
			return false;
		}

		if (Rs2Player.isMoving() || Rs2Player.isAnimating())
		{
			return true;
		}

		WorldPoint markLocation = markOfGrace.getWorldLocation();
		var markLocalLocation = markOfGrace.getLocalLocation();
		if (markLocation == null || markLocalLocation == null)
		{
			return false;
		}

		if (!Rs2Camera.isTileOnScreen(markLocalLocation))
		{
			Rs2Camera.turnTo(markLocalLocation);
			sleep(300, 600);
			return true;
		}

		int markCount = Rs2Inventory.itemQuantity(ItemID.GRACE);
		if (!pickupMarkOfGrace(markOfGrace))
		{
			return false;
		}
		pendingMarkOfGraceLocation = markLocation;
		pendingMarkOfGraceCount = markCount;
		pendingMarkOfGraceStartedAt = System.currentTimeMillis();

		sleepUntil(() -> shuttingDown || markPickupResolved() || Rs2Player.isMoving(), 1800);
		if (markPickupResolved() || shuttingDown)
		{
			clearPendingMarkOfGrace();
		}
		return true;
	}

	private boolean markPickupResolved()
	{
		return pendingMarkOfGraceLocation != null
			&& (Rs2Inventory.itemQuantity(ItemID.GRACE) > pendingMarkOfGraceCount
			|| !hasLootableMarkAt(pendingMarkOfGraceLocation));
	}

	private void clearPendingMarkOfGrace()
	{
		pendingMarkOfGraceLocation = null;
		pendingMarkOfGraceCount = 0;
		pendingMarkOfGraceStartedAt = 0;
		lastMarkOfGraceScanAt = 0;
	}

	private boolean hasLootableMarkAt(WorldPoint markLocation)
	{
		return Microbot.getRs2TileItemCache().query()
			.fromWorldView()
			.withId(ItemID.GRACE)
			.where(Rs2TileItemModel::isLootAble)
			.where(item -> markLocation.equals(item.getWorldLocation()))
			.first() != null;
	}

	private boolean pickupMarkOfGrace(Rs2TileItemModel markOfGrace)
	{
		try
		{
			ItemComposition item = Microbot.getClientThread()
				.runOnClientThreadOptional(() -> Microbot.getClient().getItemDefinition(markOfGrace.getId()))
				.orElse(null);
			if (item == null)
			{
				return false;
			}

			LocalPoint localPoint = markOfGrace.getLocalLocation();
			if (localPoint == null)
			{
				return false;
			}

			MenuAction menuAction = getGroundItemMenuAction(item, "Take");
			if (menuAction == null)
			{
				return false;
			}

			if (!Rs2Camera.isTileOnScreen(localPoint))
			{
				Rs2Camera.turnTo(localPoint);
			}

			Polygon canvasTile = Perspective.getCanvasTilePoly(Microbot.getClient(), localPoint);
			Rectangle clickBounds = canvasTile == null
				? new Rectangle(1, 1, Microbot.getClient().getCanvasWidth(), Microbot.getClient().getCanvasHeight())
				: canvasTile.getBounds();

			Microbot.doInvoke(new NewMenuEntry()
					.param0(localPoint.getSceneX())
					.param1(localPoint.getSceneY())
					.opcode(menuAction.getId())
					.identifier(markOfGrace.getId())
					.itemId(-1)
					.option("Take")
					.target("<col=ff9040>" + item.getName())
					.worldViewId(localPoint.getWorldView()),
				clickBounds);
			return true;
		}
		catch (Exception ex)
		{
			Microbot.log("Failed to pick up Mark of grace: " + ex.getMessage());
			return false;
		}
	}

	private MenuAction getGroundItemMenuAction(ItemComposition item, String action)
	{
		String[] groundActions = Rs2Reflection.getGroundItemActions(item);
		for (int i = 0; i < groundActions.length; i++)
		{
			String groundAction = groundActions[i];
			if (groundAction != null && groundAction.equalsIgnoreCase(action))
			{
				return groundItemMenuAction(i);
			}
		}
		return null;
	}

	private MenuAction groundItemMenuAction(int index)
	{
		switch (index)
		{
			case 0:
				return MenuAction.GROUND_ITEM_FIRST_OPTION;
			case 1:
				return MenuAction.GROUND_ITEM_SECOND_OPTION;
			case 2:
				return MenuAction.GROUND_ITEM_THIRD_OPTION;
			case 3:
				return MenuAction.GROUND_ITEM_FOURTH_OPTION;
			case 4:
				return MenuAction.GROUND_ITEM_FIFTH_OPTION;
			default:
				return null;
		}
	}

	private boolean shouldPerformAlch(TileObject gameObject)
	{
		if (!config.alchemy())
		{
			clearAlchDecision();
			return false;
		}

		WorldPoint obstacleLocation = gameObject.getWorldLocation();
		if (gameObject.getId() == alchDecisionObstacleId && obstacleLocation.equals(alchDecisionObstacleLocation))
		{
			return alchDecisionShouldAlch;
		}

		alchDecisionObstacleId = gameObject.getId();
		alchDecisionObstacleLocation = obstacleLocation;
		alchDecisionShouldAlch = ThreadLocalRandom.current().nextInt(100) >= config.alchSkipChance();
		return alchDecisionShouldAlch;
	}

	private boolean performEfficientAlch(TileObject gameObject, String alchItem, int agilityExp)
	{
		WorldPoint playerLocation = Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getWorldLocation());

		if (gameObject.getWorldLocation().distanceTo(playerLocation) >= 5)
		{
			// Efficient alching: click, alch, click
			if (interactWithObstacle(gameObject))
			{
				sleep(100, 200);
				Rs2Magic.alch(alchItem, 50, 75);
				interactWithObstacle(gameObject);
				boolean completed = getActiveHandler().waitForCompletion(agilityExp,
					Microbot.getClientThread().invoke(() -> Microbot.getClient().getLocalPlayer().getWorldLocation()).getPlane());

				if (!completed) {
					// Timeout during efficient alching - log warning
					long now = System.currentTimeMillis();
					if (now - lastTimeoutWarning > 30000) {
						Microbot.log("Obstacle completion timed out during efficient alching");
						lastTimeoutWarning = now;
					}
					return false;  // Return false to indicate alch sequence failed
				}
				
				Rs2Antiban.actionCooldown();
				Rs2Antiban.takeMicroBreakByChance();
				lastAgilityXp = Microbot.getClient().getSkillExperience(Skill.AGILITY);
				clearAlchDecision();
				return true;
			}
		}
		return false;
	}

	private boolean performNormalAlch(String alchItem)
	{
		int initialCount = Rs2Inventory.itemQuantity(alchItem);
		Rs2Magic.alch(alchItem, 50, 75);
		sleepUntil(() -> shuttingDown || Rs2Inventory.itemQuantity(alchItem) < initialCount, 1200);
		alchDecisionShouldAlch = false;
		return true;
	}

	private void clearAlchDecision()
	{
		alchDecisionObstacleLocation = null;
		alchDecisionObstacleId = -1;
		alchDecisionShouldAlch = false;
	}

	private boolean interactWithObstacle(TileObject gameObject)
	{
		return Rs2GameObject.interact(gameObject);
	}

	/**
	 * Clicks the obstacle with rare human input errors layered on: a very rare wasted "misclick"
	 * just off the object before clicking it properly, and a rare redundant second click on the
	 * same obstacle. Both are genuinely rare (see MISCLICK/MULTI_CLICK_PER_MILLE) so they read as
	 * the occasional slip a real player makes, not a repeating tic. Neither changes the outcome -
	 * the obstacle is still interacted with normally.
	 */
	private boolean interactWithObstacleHumanized(TileObject gameObject)
	{
		maybeMisclick(gameObject);
		boolean interacted = interactWithObstacle(gameObject);
		if (interacted && ThreadLocalRandom.current().nextInt(1000) < MULTI_CLICK_PER_MILLE)
		{
			// A second, redundant click on the same obstacle - harmless (same menu target), just human.
			sleep(80, 240);
			interactWithObstacle(gameObject);
		}
		return interacted;
	}

	/**
	 * Very rarely fires one plain left-click just outside the obstacle's clickbox - a genuine
	 * near-miss - then pauses briefly. The real click that follows still lands on the obstacle, so
	 * the only effect is a wasted "oops" click, the way a person occasionally misjudges the edge.
	 */
	private void maybeMisclick(TileObject gameObject)
	{
		if (ThreadLocalRandom.current().nextInt(1000) >= MISCLICK_PER_MILLE)
		{
			return;
		}
		try
		{
			Rectangle box = Rs2UiHelper.getObjectClickbox(gameObject);
			if (box == null || box.isEmpty() || (box.x == 1 && box.y == 1))
			{
				return;
			}
			// A few px beyond one random edge of the clickbox - close enough to be a plausible miss.
			int slip = ThreadLocalRandom.current().nextInt(2, 7);
			int x;
			int y;
			switch (ThreadLocalRandom.current().nextInt(4))
			{
				case 0: x = box.x - slip; y = box.y + box.height / 2; break;             // left
				case 1: x = box.x + box.width + slip; y = box.y + box.height / 2; break; // right
				case 2: x = box.x + box.width / 2; y = box.y - slip; break;              // top
				default: x = box.x + box.width / 2; y = box.y + box.height + slip; break; // bottom
			}
			if (x <= 1 && y <= 1)
			{
				return;
			}
			Microbot.getMouse().click(new Point(x, y));
			sleep(150, 400);
		}
		catch (Exception ignored)
		{
			// Best-effort - a simulated misclick must never break the real interaction.
		}
	}

	/**
	 * Resolves the next obstacle after {@code currentObstacle} by simple list lookahead
	 * (index + 1), returning the nearest matching in-scene object within 20 tiles, or {@code null}
	 * if there's no sane candidate. Lookahead-by-position is exact for the linear courses and a
	 * best-guess for the four branching ones (Prifddinas, Pyramid, Werewolf, Brimhaven) - but the
	 * on-screen guard inside the tracker makes a wrong guess a harmless no-op rather than a hover
	 * across the map, so no per-course special-casing is needed.
	 */
	private TileObject resolveNextObstacle(AgilityCourseHandler courseHandler, TileObject currentObstacle, int currentObstacleIndex)
	{
		try
		{
			if (currentObstacleIndex < 0 || currentObstacle == null || Microbot.naturalMouse == null)
			{
				return null;
			}

			List<AgilityObstacleModel> obstacles = courseHandler.getObstacles();
			int nextIndex = currentObstacleIndex + 1;
			if (nextIndex >= obstacles.size())
			{
				return null;
			}

			int nextObjectId = obstacles.get(nextIndex).getObjectID();
			WorldPoint fromLocation = currentObstacle.getWorldLocation();
			return Rs2GameObject.getAll(obj ->
					obj.getId() == nextObjectId && obj.getWorldLocation().distanceTo(fromLocation) <= 20)
				.stream()
				.min(Comparator.comparingInt(obj -> obj.getWorldLocation().distanceTo(fromLocation)))
				.orElse(null);
		}
		catch (Exception ex)
		{
			// Best-effort only - never let a lookahead failure affect the actual obstacle flow.
			Microbot.log("resolveNextObstacle skipped: " + ex.getMessage());
			return null;
		}
	}

	/**
	 * Human-like hover that keeps the cursor sitting ON the next obstacle and follows it as the
	 * camera pans during traversal. A person doesn't chase the exact centre - an object has area,
	 * so once the cursor is on it they leave it and only make a small correction when the object
	 * has drifted out from under it. That's exactly this: {@link #trackHover} rests while the cursor
	 * is comfortably inside an inner region of the clickbox, and nudges it back to a fresh natural
	 * random point only once drift carries it toward the edge. Reliable (always on the object, ready
	 * to click) yet not a metronome - corrections fire only when the object actually moves, so their
	 * timing is as irregular as the camera itself.
	 * <p>
	 * Runs on a daemon thread concurrently with (and never touching) {@code waitForCompletion}, so
	 * it's safe with the courses that override completion logic (Falador, both Colossal Wyrms).
	 * Movement only, never a click. Off-screen next obstacle -> {@link #trackHover} no-ops, so
	 * branching courses (dark-hole teleports, fail-branches) simply don't hover.
	 */
	private void startHoverTracking(TileObject nextObstacle)
	{
		stopHoverTracking();
		if (nextObstacle == null || Microbot.naturalMouse == null)
		{
			return;
		}
		// Track most of the time, but occasionally a person just doesn't pre-move to the next one.
		if (ThreadLocalRandom.current().nextInt(100) >= HOVER_CHANCE_PERCENT)
		{
			return;
		}
		// Small human lead-in, then follow the object at a steady check cadence. Moves only happen
		// when drift pushes the cursor off, so the cadence being fixed doesn't make it robotic.
		long initialDelay = ThreadLocalRandom.current().nextLong(50, 250);
		hoverFuture = hoverExecutor.scheduleWithFixedDelay(
			() -> trackHover(nextObstacle), initialDelay, HOVER_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
	}

	/**
	 * One drift-correction tick: if the cursor has drifted out of the comfortable inner region of
	 * the obstacle's clickbox, move it back to a fresh natural random point inside the box.
	 * Otherwise do nothing - the cursor is still on the object, no need to fidget. No-ops when the
	 * object isn't really on screen (clickbox null/empty or {@code (1,1)}).
	 */
	private void trackHover(TileObject obstacle)
	{
		try
		{
			Rectangle box = Rs2UiHelper.getObjectClickbox(obstacle);
			if (box == null || box.isEmpty() || (box.x == 1 && box.y == 1))
			{
				return;
			}
			// Objects have area: rest while the cursor is comfortably inside, only correct once drift
			// has carried it past the inner margin so we're not re-moving on every tick.
			Rectangle comfort = insetRect(box, HOVER_COMFORT_MARGIN);
			java.awt.Point mouse = Microbot.getMouse().getMousePosition();
			if (mouse != null && comfort != null && comfort.contains(mouse))
			{
				return;
			}
			// Land the correction in a smaller central region rather than a cursor-biased edge point.
			// getClickingPoint() biases toward the current cursor - which is at the edge when we
			// correct - so it would drop the cursor right back near the edge and drift out again
			// almost immediately (twitchy). Re-centring gives it room, so corrections are fewer,
			// better spaced, and each is a slightly longer, smoother move.
			Rectangle target = insetRect(box, HOVER_TARGET_MARGIN);
			Point point = (target != null)
				? new Point(ThreadLocalRandom.current().nextInt(target.x, target.x + target.width + 1),
					ThreadLocalRandom.current().nextInt(target.y, target.y + target.height + 1))
				: Rs2UiHelper.getClickingPoint(box, true);
			if (point.getX() <= 1 && point.getY() <= 1)
			{
				return;
			}
			Microbot.naturalMouse.moveTo(point.getX(), point.getY());
		}
		catch (Exception ignored)
		{
			// Best-effort - a hover hiccup must never affect the obstacle flow.
		}
	}

	/**
	 * Returns {@code box} shrunk by {@code margin} (a fraction of each dimension) on every side, or
	 * {@code null} if that collapses it to nothing - used to derive the "resting" and "re-centre"
	 * regions from an obstacle's clickbox.
	 */
	private Rectangle insetRect(Rectangle box, double margin)
	{
		Rectangle r = new Rectangle(box);
		r.grow(-(int) Math.round(box.width * margin), -(int) Math.round(box.height * margin));
		return (r.width > 0 && r.height > 0) ? r : null;
	}

	private void stopHoverTracking()
	{
		if (hoverFuture != null)
		{
			hoverFuture.cancel(true);
			hoverFuture = null;
		}
	}
}
