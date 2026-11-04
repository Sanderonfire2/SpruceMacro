package com.sprucefarm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.Mth;
import com.sprucefarm.logic.LookMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The farm state machine. All actions are issued from the client tick, one
 * verified action at a time, and the player's position is never changed.
 */
public final class FarmController {
	private static final Logger LOGGER = LoggerFactory.getLogger("sprucefarm");

	public enum Stage {
		IDLE,
		PLANT,
		GROW,
		HARVEST,
		SELL,
		RESTOCK,
		STOPPED,
		ERROR
	}

	private final Minecraft client;
	private final FarmConfig config;
	private final Random random = new Random();

	private Stage stage = Stage.IDLE;
	private Stage restockResume = Stage.PLANT;
	private String status = "Idle. Stand still on the chests and press Start.";
	private String error = "";
	/** A non-fatal problem worth showing in the panel, unlike {@link #error}. */
	private String warning = "";
	private int skippedHidden;
	private boolean paused;
	private int cooldown;
	private int ticks;
	private Vec3 anchor;

	private final List<PlantSpot> spots = new ArrayList<>();
	private List<ChestGroup> chests = new ArrayList<>();
	private int pads;
	private int blockedSpots;
	private int unreachableSpots;

	private FarmTask task;
	private boolean reopenPanel;
	private long chestHint = Long.MIN_VALUE;

	private int cycle;
	private FarmStats cycleStats = new FarmStats();
	private FarmStats totalStats = new FarmStats();

	private int growCursor;
	private int harvestCursor;
	private int blockedWait;
	private boolean starting;
	private int startSettle;
	private Vec3 settlePos;
	private boolean breaking;
	private boolean breakingObstruction;
	private boolean breakingLeftover;
	private BlockPos breakPos;
	private int breakTicks;
	private int leftoverVision;
	private int leftoverWait;
	private int lastLeftoverCount = -1;
	private int leftoverQuiet;
	private int fellWaitBudget;

	private enum PendingType {
		PLANT, GROW, HARVEST, HARVEST_LEFTOVER, CLEAR_OBSTRUCTION
	}

	/** What one pending-action tick did to the controller. */
	private enum PendingResult {
		/** Nothing was queued; the stage may choose a target this tick. */
		NONE,
		/** The pending action owns this tick (turning, settling, waiting, failing). */
		BUSY,
		/** The click or break went out this tick, so the next target may be aimed now. */
		EXECUTED
	}

	private record PendingAction(PendingType type, PlantSpot target, BlockHitResult hit,
			int aimTick, int settleTicks, InteractionHelper.Aim aim) {
	}

	private PendingAction pending;

	public FarmController(Minecraft client, FarmConfig config) {
		this.client = client;
		this.config = config;
	}

	public Minecraft client() {
		return client;
	}

	public FarmConfig config() {
		return config;
	}

	public Stage stage() {
		return stage;
	}

	public String status() {
		return status;
	}

	public String error() {
		return error;
	}

	public String warning() {
		return warning;
	}

	public int skippedHidden() {
		return skippedHidden;
	}

	public boolean paused() {
		return paused;
	}

	public boolean isActive() {
		return stage != Stage.IDLE && stage != Stage.STOPPED && stage != Stage.ERROR;
	}

	public int cycle() {
		return cycle;
	}

	public FarmStats cycleStats() {
		return cycleStats;
	}

	public FarmStats totalStats() {
		return totalStats;
	}

	public List<PlantSpot> spots() {
		return spots;
	}

	public List<ChestGroup> chests() {
		return chests;
	}

	public int pads() {
		return pads;
	}

	public int blockedSpots() {
		return blockedSpots;
	}

	public int unreachableSpots() {
		return unreachableSpots;
	}

	public int tickCount() {
		return ticks;
	}

	public void setChestHint(long key) {
		chestHint = key;
	}

	public void updateChestStock(long key, int count) {
		for (ChestGroup group : chests) {
			if (group.key == key) {
				group.cachedBonemeal = count;
				group.lastCheckedTick = ticks;
			}
		}
	}

	public String stageLabel() {
		return switch (stage) {
			case IDLE -> "Idle";
			case PLANT -> "Planting";
			case GROW -> "Growing";
			case HARVEST -> "Harvesting";
			case SELL -> "Selling";
			case RESTOCK -> "Restocking";
			case STOPPED -> "Stopped";
			case ERROR -> "Error";
		};
	}

	public void start() {
		if (client.player == null || client.level == null) {
			fail("Join a world first.");
			return;
		}
		if (task != null) {
			task.cancel();
			task = null;
		}
		pending = null;
		if (breaking) {
			InteractionHelper.stopBreaking(client);
			breaking = false;
			breakingObstruction = false;
		}
		if (client.player.containerMenu != client.player.inventoryMenu) {
			client.player.closeContainer();
		}
		if (client.screen != null && !(client.screen instanceof FarmScreen)) {
			client.setScreen(null);
		}

		paused = false;
		error = "";
		warning = "";
		skippedHidden = 0;
		status = "Scanning the farm";
		cycle = 0;
		cycleStats.reset();
		totalStats.reset();
		anchor = client.player.position();
		chestHint = Long.MIN_VALUE;

		rescanLayout();
		rescanChests();

		if (spots.isEmpty()) {
			fail("No grass planting spots found within reach. Stand on the chests in the middle of the farm.");
			return;
		}
		if (config.requireFullLayout
			&& (spots.size() != config.expectedSpots || pads != config.expectedPads)) {
			fail("Detected " + spots.size() + "/" + config.expectedSpots + " spots on "
				+ pads + "/" + config.expectedPads + " pads (" + blockedSpots + " blocked, "
				+ unreachableSpots + " out of reach). Fix the layout or disable the full-layout check.");
			return;
		}
		if (!hasAxe()) {
			fail("No axe found. If your special axe is not auto-detected, hold it and press 'Set axe'.");
			return;
		}

		// A server transfer/teleport can leave the client's block-sequence
		// state briefly out of step.  Wait for a stable, grounded player
		// before the first interaction instead of firing immediately.
		starting = true;
		startSettle = 40;
		settlePos = client.player.position();
		stage = Stage.PLANT;
		status = "Stabilising before the first action...";
	}

	public void stop() {
		if (task != null) {
			task.cancel();
			task = null;
		}
		if (breaking) {
			InteractionHelper.stopBreaking(client);
			breaking = false;
			breakingObstruction = false;
		}
		pending = null;
		starting = false;
		stage = Stage.STOPPED;
		paused = false;
		status = "Stopped. No new actions will be performed.";
		reopenPanel = false;
		if (client.player != null && client.player.containerMenu != client.player.inventoryMenu) {
			client.player.closeContainer();
		}
		if (client.screen != null && !(client.screen instanceof FarmScreen)) {
			client.setScreen(null);
		}
	}

	public void togglePause() {
		if (!isActive()) {
			return;
		}
		paused = !paused;
		if (paused) {
			if (breaking) {
				InteractionHelper.stopBreaking(client);
				breaking = false;
				breakingObstruction = false;
			}
			status = "Paused";
		} else {
			status = "Resumed";
		}
	}

	public void rescan() {
		rescanLayout();
		rescanChests();
		warning = "";
		skippedHidden = 0;
		markHiddenSpots();
		if (!isActive() || paused) {
			status = "Rescanned: " + spots.size() + " spots on " + pads + " pads.";
		}
	}

	public void tick() {
		ticks++;
		if (client.player == null || client.level == null) {
			if (task != null) {
				task.cancel();
				task = null;
			}
			stage = Stage.IDLE;
			status = "Idle. Join a world.";
			spots.clear();
			chests.clear();
			pads = 0;
			blockedSpots = 0;
			unreachableSpots = 0;
			anchor = null;
			return;
		}

		if (cooldown > 0) {
			cooldown--;
		}

		if (starting) {
			if (paused) {
				status = "Paused";
				return;
			}
			LocalPlayer startingPlayer = client.player;
			boolean otherScreen = client.screen != null && !(client.screen instanceof FarmScreen);
			if (startingPlayer.isDeadOrDying()) {
				starting = false;
				fail("You died. Farming stopped.");
				return;
			}
			if (otherScreen || !startingPlayer.onGround()
				|| startingPlayer.position().distanceTo(settlePos) > 0.05D) {
				settlePos = startingPlayer.position();
				startSettle = 40;
				status = otherScreen ? "Waiting: close the open screen."
					: "Waiting until you stand still before starting...";
				cooldown = 1;
				return;
			}
			if (--startSettle > 0) {
				status = "Starting in " + ((startSettle + 19) / 20) + "s...";
				cooldown = 1;
				return;
			}
			starting = false;
			beginCycle();
			return;
		}

		if (stage == Stage.IDLE || stage == Stage.STOPPED || stage == Stage.ERROR) {
			return;
		}

		if (task != null) {
			tickTask();
			return;
		}

		if (paused) {
			status = "Paused";
			return;
		}

		LocalPlayer player = client.player;
		if (player.isDeadOrDying()) {
			fail("You died. Farming stopped.");
			return;
		}
		if (anchor != null && player.position().distanceTo(anchor) > 0.75D) {
			fail("You moved from the farm centre. Stand still and press Start.");
			return;
		}
		if (client.screen != null && !(client.screen instanceof FarmScreen)) {
			status = "Waiting: close the open screen.";
			return;
		}
		if (player.containerMenu != player.inventoryMenu) {
			// A container id mismatch (for example sending an inventory
			// click to container 0 while a chest is open) is rejected by
			// server menu validation and can look like a bad packet sequence.
			status = "Waiting: close the open container.";
			return;
		}
		if (player.isShiftKeyDown()) {
			status = "Waiting: release sneak.";
			return;
		}
		if (player.isUsingItem()) {
			status = "Waiting: item in use.";
			return;
		}
		if (cooldown > 0) {
			return;
		}

		if (pending != null) {
			PendingResult result = tickPending();
			if (result == PendingResult.BUSY) {
				return;
			}
			if (result == PendingResult.EXECUTED && breaking) {
				// Keep the camera on the trunk while the break runs.
				return;
			}
			// An executed click leaves this tick free: choose and turn to the
			// next target now.  Its click goes out on the next tick, after the
			// movement packet with this turn, so fast mode still performs one
			// click per tick without ever looking and clicking in one tick.
		}

		if (breaking && tickBreaking()) {
			return;
		}

		switch (stage) {
			case PLANT -> tickPlant();
			case GROW -> tickGrow();
			case HARVEST -> tickHarvest();
			case SELL -> {
				if (task == null) {
					if (InventoryHelper.sellableCount(player, config.keepSaplings) <= 0) {
						afterSell();
					} else {
						beginTask(new SellTask(this));
						status = "Selling whole stacks";
					}
				}
			}
			case RESTOCK -> {
				if (task == null) {
					if (chests.isEmpty()) {
						rescanChests();
					}
					if (chests.isEmpty()) {
						fail("No chests detected within reach under the player.");
						return;
					}
					// Always open the chests instead of trusting a cached zero.
					// A single unsynchronised read used to mark them permanently
					// empty, so refilled chests were never opened again.
					beginTask(new RestockTask(this, chests, chestHint));
					status = "Restocking bonemeal";
				}
			}
			default -> {
			}
		}
	}

	private void beginCycle() {
		if (client.player == null) {
			return;
		}
		refreshAllSpots();
		markHiddenSpots();
		boolean treeStanding = spots.stream()
			.anyMatch(spot -> !spot.abandoned && spot.state == SpotState.GROWN);
		if (treeStanding) {
			// A tree left from an earlier session is harvested, not treated as
			// an error: the axe and the sell flow are exactly what is needed.
			cycle++;
			cycleStats.reset();
			for (PlantSpot spot : spots) {
				spot.resetCycle();
			}
			growCursor = 0;
			harvestCursor = 0;
			blockedWait = 0;
			breaking = false;
			breakingObstruction = false;
			breakingLeftover = false;
			leftoverVision = 0;
			leftoverWait = 0;
			lastLeftoverCount = -1;
			leftoverQuiet = 0;
			fellWaitBudget = config.fellFinishTimeoutTicks;
			stage = Stage.HARVEST;
			status = "Cycle " + cycle + ": harvesting trees left standing";
			return;
		}
		if (inventoryNeedsSell(client.player)) {
			// The harvest is finished and the inventory cannot take another
			// full cycle.  Sell now, once, instead of after every cycle.
			cycle++;
			cycleStats.reset();
			for (PlantSpot spot : spots) {
				spot.resetCycle();
			}
			growCursor = 0;
			harvestCursor = 0;
			blockedWait = 0;
			breaking = false;
			breakingObstruction = false;
			breakingLeftover = false;
			leftoverVision = 0;
			leftoverWait = 0;
			lastLeftoverCount = -1;
			leftoverQuiet = 0;
			fellWaitBudget = config.fellFinishTimeoutTicks;
			stage = Stage.SELL;
			status = "Inventory nearly full: selling before the next cycle";
			return;
		}
		int empty = (int) spots.stream()
			.filter(spot -> !spot.abandoned && spot.state == SpotState.EMPTY).count();
		int saplings = saplings();
		if (saplings < empty) {
			fail("Not enough spruce saplings: have " + saplings + ", need " + empty + ".");
			return;
		}
		if (bonemeal() <= 0) {
			beginRestock(Stage.PLANT);
			return;
		}

		cycle++;
		cycleStats.reset();
		for (PlantSpot spot : spots) {
			spot.resetCycle();
		}
		growCursor = 0;
		harvestCursor = 0;
		blockedWait = 0;
		breaking = false;
		breakingObstruction = false;
		breakingLeftover = false;
		leftoverVision = 0;
		leftoverWait = 0;
		lastLeftoverCount = -1;
		leftoverQuiet = 0;
		fellWaitBudget = config.fellFinishTimeoutTicks;
		stage = Stage.PLANT;
		status = "Cycle " + cycle + ": planting";
	}

	/**
	 * Queues one interaction for a later tick.  The turn is applied now and
	 * travels in this tick's normal movement packet; the click/break is only
	 * sent once the server has that rotation, exactly like a player who looks
	 * first and clicks after the server has received the new angle.  Sending
	 * the rotation and the click in the same tick is what the server kicked as
	 * {@code Invalid sequence}.
	 */
	private void queueAction(PendingType type, PlantSpot target, BlockHitResult hit) {
		if (client.player == null) {
			return;
		}
		// Fast mode turns instantly but still lets the vanilla movement packet
		// carry the rotation; the click follows on the next tick.
		InteractionHelper.Aim aim = config.fastMode
			? InteractionHelper.snapLook(client.player, hit.getLocation())
			: null;
		pending = new PendingAction(type, target, hit, ticks, 0, aim);
		cooldown = 1;
		status = "Aiming at " + label(target);
	}

	/**
	 * Runs the queued action.  BUSY keeps the controller out of the stage
	 * logic; EXECUTED means the click/break was sent and the next target can be
	 * aimed in the same tick so fast mode keeps one action per tick.
	 */
	private PendingResult tickPending() {
		PendingAction action = pending;
		if (action == null) {
			return PendingResult.NONE;
		}
		if (client.player == null || client.level == null || stage == Stage.STOPPED || stage == Stage.ERROR) {
			pending = null;
			return PendingResult.NONE;
		}
		refreshAllSpots();
		if (stage == Stage.ERROR) {
			return PendingResult.BUSY;
		}
		PlantSpot target = action.target;
		BlockState current = client.level.getBlockState(action.hit.getBlockPos());
		switch (action.type) {
			case PLANT -> {
				if (target.state != SpotState.EMPTY || !client.level.getBlockState(target.saplingPos).isAir()) {
					pending = null;
					return PendingResult.NONE;
				}
			}
			case GROW -> {
				if (target.state != SpotState.PLANTED
					|| !client.level.getBlockState(target.saplingPos).is(Blocks.SPRUCE_SAPLING)) {
					pending = null;
					return PendingResult.NONE;
				}
			}
			case HARVEST -> {
				if (target.state != SpotState.GROWN || !isSpruceLog(client.level.getBlockState(target.saplingPos))) {
					pending = null;
					return PendingResult.NONE;
				}
			}
			case HARVEST_LEFTOVER -> {
				if (!isSpruceLog(current)) {
					pending = null;
					return PendingResult.NONE;
				}
			}
			case CLEAR_OBSTRUCTION -> {
				if (!isClearableObstruction(current)) {
					pending = null;
					return PendingResult.NONE;
				}
			}
		}

		if (config.fastMode) {
			// The turn was applied when the action was queued and already went
			// out in that tick's movement packet, so the click may fire now.
			// If the camera moved since (mouse input, another mod) the server
			// does not know the aim yet: turn again, wait one tick, retry.
			if (action.aim == null || action.aimTick >= ticks
				|| !InteractionHelper.rotationMatches(client.player, action.aim)) {
				InteractionHelper.Aim aim = InteractionHelper.snapLook(client.player, action.hit.getLocation());
				pending = new PendingAction(action.type, target, action.hit, ticks, 0, aim);
				cooldown = 1;
				status = "Aiming at " + label(target);
				return PendingResult.BUSY;
			}
		} else {
			if (!InteractionHelper.aim(client.player, action.hit.getLocation())) {
				pending = new PendingAction(action.type, target, action.hit, ticks, 0, action.aim);
				cooldown = 1;
				status = "Aiming at " + label(target);
				return PendingResult.BUSY;
			}
			if (action.settleTicks == 0) {
				// Slow mode lets LocalPlayer send the final rotation packet on
				// the next tick, then clicks after a short human-like pause.
				pending = new PendingAction(action.type, target, action.hit, ticks, config.aimSettleTicks, action.aim);
				cooldown = 1;
				status = "Settling aim at " + label(target);
				return PendingResult.BUSY;
			}
			if (ticks <= action.aimTick + action.settleTicks) {
				cooldown = 1;
				status = "Settling aim at " + label(target);
				return PendingResult.BUSY;
			}
		}

		if (action.type == PendingType.PLANT) {
			// Bonemeal is applied to the plant at the hit position; the server
			// checks reach, not the crosshair.  Planting keeps the stricter
			// visible-top-face check because a misplaced click would place a
			// sapling on the wrong block.
			BlockHitResult camera = InteractionHelper.cameraHit(client, action.hit.getBlockPos());
			if (camera != null && (action.type != PendingType.PLANT || camera.getDirection() == Direction.UP)) {
				action = new PendingAction(action.type, target, camera, action.aimTick, action.settleTicks, action.aim);
			} else if (camera == null && action.type == PendingType.PLANT
				&& InteractionHelper.hitCrossesOnlyPlants(client, action.hit, config.interactThroughSigns)) {
				// The crosshair cuts a sapling's cross before the soil.  Keep the
				// top-face hit: the plant has no collision shape, so this is the
				// same placement a player gets by clicking through it.
			} else if (config.directFallback) {
				// The user explicitly opted into through-block interactions.
				// Use the same point the aim settled on, clamped to the real
				// 4.5-block reach, so at least the hit vector is a legal one.
				BlockHitResult direct = InteractionHelper.directHitNear(
					client, action.hit.getBlockPos(), action.hit.getDirection()
				);
				if (direct == null) {
					target.visionFailures++;
					target.nextAttemptTick = ticks + 20;
					pending = null;
					cooldown = 1;
					status = "Target out of reach; retrying";
					return PendingResult.BUSY;
				}
				action = new PendingAction(action.type, target, direct, action.aimTick, action.settleTicks, action.aim);
			} else {
				// The synthetic sample is not what the crosshair actually
				// hits.  Do not send a placement the server can reject as a
				// fabricated/rotated hit.
				target.visionFailures++;
				target.nextAttemptTick = ticks + 20;
				pending = null;
				cooldown = 1;
				if (target.visionFailures > 12) {
					fail("The crosshair cannot hit " + label(target)
						+ " even though the block is visible. Stand closer to the centre.");
				} else {
					status = "Crosshair lost the target; retrying";
				}
				return PendingResult.BUSY;
			}
		}

		switch (action.type) {
			case PLANT -> {
				InteractionHelper.EnsureResult ensure = InteractionHelper.ensureItem(
					client, stack -> stack.is(Items.SPRUCE_SAPLING), config
				);
				if (ensure.state() == InteractionHelper.Ensure.MISSING) {
					fail("Out of spruce saplings.");
					return PendingResult.BUSY;
				}
				if (ensure.state() == InteractionHelper.Ensure.BUSY) {
					cooldown = guiDelay();
					status = ensure.detail();
					return PendingResult.BUSY;
				}
				// The server block update for this click must have a newer
				// sequence than this value; only that update counts as planted.
				target.plantClickSequence = ServerState.sequence();
				target.plantClickTick = ticks;
				target.serverConfirmedPlant = false;
				target.lastHitDistance = client.player.getEyePosition().distanceTo(action.hit.getLocation());
				target.lastHitFace = action.hit.getDirection().getName();
				InteractionHelper.useOn(client, action.hit);
				target.plantAttempts++;
				target.nextAttemptTick = ticks + actionDelay() + 2;
				cooldown = actionDelay();
				long planted = spots.stream().filter(spot -> spot.serverConfirmedPlant).count();
				status = "Planting: " + planted + "/" + spots.size() + " confirmed by the server";
			}
			case GROW -> {
				InteractionHelper.EnsureResult ensure = InteractionHelper.ensureItem(
					client, stack -> stack.is(Items.BONE_MEAL), config
				);
				if (ensure.state() == InteractionHelper.Ensure.MISSING) {
					pending = null;
					beginRestock(Stage.GROW);
					return PendingResult.BUSY;
				}
				if (ensure.state() == InteractionHelper.Ensure.BUSY) {
					cooldown = guiDelay();
					status = ensure.detail();
					return PendingResult.BUSY;
				}
				InteractionHelper.useOn(client, action.hit);
				target.growAttempts++;
				target.nextAttemptTick = ticks + actionDelay();
				cooldown = actionDelay();
				long grown = spots.stream().filter(spot -> spot.state == SpotState.GROWN).count();
				status = "Growing: " + grown + "/" + spots.size() + " trees | bonemeal " + bonemeal();
			}
			case HARVEST -> {
				InteractionHelper.EnsureResult ensure = InteractionHelper.ensureItem(
					client, stack -> InventoryHelper.isAxe(stack, config), config
				);
				if (ensure.state() == InteractionHelper.Ensure.MISSING) {
					fail("The axe is no longer in the inventory.");
					return PendingResult.BUSY;
				}
				if (ensure.state() == InteractionHelper.Ensure.BUSY) {
					cooldown = guiDelay();
					status = ensure.detail();
					return PendingResult.BUSY;
				}
				InteractionHelper.startBreaking(client, target.saplingPos);
				breaking = true;
				breakingObstruction = false;
				breakingLeftover = false;
				breakPos = target.saplingPos;
				breakTicks = 1;
				cooldown = breakDelay();
				status = "Harvesting tree at " + label(target);
			}
			case HARVEST_LEFTOVER -> {
				InteractionHelper.EnsureResult ensure = InteractionHelper.ensureItem(
					client, stack -> InventoryHelper.isAxe(stack, config), config
				);
				if (ensure.state() == InteractionHelper.Ensure.MISSING) {
					fail("The axe is no longer in the inventory.");
					return PendingResult.BUSY;
				}
				if (ensure.state() == InteractionHelper.Ensure.BUSY) {
					cooldown = guiDelay();
					status = ensure.detail();
					return PendingResult.BUSY;
				}
				BlockPos logPos = action.hit.getBlockPos().immutable();
				InteractionHelper.startBreaking(client, logPos);
				breaking = true;
				breakingObstruction = false;
				breakingLeftover = true;
				breakPos = logPos;
				breakTicks = 1;
				lastLeftoverCount = -1;
				leftoverWait = 0;
				leftoverQuiet = 0;
				fellWaitBudget = config.fellFinishTimeoutTicks;
				cycleStats.extraFells++;
				totalStats.extraFells++;
				cooldown = breakDelay();
				status = "Removing the log the axe left at " + pos(logPos)
					+ " (extra fell " + cycleStats.extraFells + ")";
			}
			case CLEAR_OBSTRUCTION -> {
				InteractionHelper.EnsureResult ensure = InteractionHelper.ensureItem(
					client, stack -> InventoryHelper.isAxe(stack, config), config
				);
				if (ensure.state() == InteractionHelper.Ensure.MISSING) {
					fail("The axe is no longer in the inventory.");
					return PendingResult.BUSY;
				}
				if (ensure.state() == InteractionHelper.Ensure.BUSY) {
					cooldown = guiDelay();
					status = ensure.detail();
					return PendingResult.BUSY;
				}
				InteractionHelper.startBreaking(client, target.saplingPos);
				breaking = true;
				breakingObstruction = true;
				breakingLeftover = false;
				breakPos = target.saplingPos;
				breakTicks = 1;
				target.obstructionClears++;
				cooldown = breakDelay();
				status = "Clearing obstruction at " + label(target);
			}
		}
		pending = null;
		return PendingResult.EXECUTED;
	}

	private void tickPlant() {
		refreshAllSpots();
		if (stage == Stage.ERROR) {
			return;
		}
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}

		PlantSpot blocked = firstSpot(SpotState.BLOCKED);
		if (blocked != null) {
			if (tryClearSpotObstruction(blocked, player)) {
				return;
			}
			if (waitForObstruction(blocked, "Planting")) {
				return;
			}
		}
		blockedWait = 0;
		List<PlantSpot> active = activeSpots();
		if (active.isEmpty()) {
			fail("Every pad was skipped after a failed fell. Restart or rescan to retry.");
			return;
		}
		boolean anyPlanted = active.stream().anyMatch(spot -> spot.state == SpotState.PLANTED);
		if (firstSpot(SpotState.GROWN) != null) {
			fail(anyPlanted ? "A tree appeared while planting." : "A tree is still standing; harvest it first.");
			return;
		}
		boolean allPlanted = active.stream().allMatch(spot -> spot.state == SpotState.PLANTED);
		boolean allConfirmed = active.stream().allMatch(spot -> spot.serverConfirmedPlant);
		if (allPlanted && allConfirmed) {
			stage = Stage.GROW;
			growCursor = 0;
			status = "All " + spots.size() + " saplings confirmed by the server. Growing.";
			return;
		}
		if (allPlanted) {
			status = "Waiting for the server to confirm the saplings";
			cooldown = 1;
			return;
		}

		for (PlantSpot spot : active) {
			// A hidden spot gets only a few tries: it needs a through-sign
			// click, so a silent rejection is expected on a strict server and
			// twelve cancelled predictions per spot would just look like a
			// ghost-sapling macro.
			int limit = spot.hidden ? Math.min(config.maxPlantAttempts, 4) : config.maxPlantAttempts;
			if (spot.state == SpotState.EMPTY && spot.plantAttempts >= limit) {
				if (spot.hidden && config.skipHiddenSpots) {
					abandonHiddenSpots("the server never confirmed a placement through the sign box");
					return;
				}
				if (config.skipHiddenSpots && spot.serverRejections > 0) {
					// The server explicitly rolled this spot back (claim, another
					// player's block, ghost support).  One dead spot must not
					// stop the whole farm when the user needs the sign box for
					// leaf protection.  A locally blocked spot (a leaf or a
					// trunk) has no server rejection and still reports its
					// obstruction, so the user can clear it.
					abandonRejectedSpot(spot, "the server rolled back every attempt");
					return;
				}
				String actual = client.level == null ? "unknown"
					: client.level.getBlockState(spot.saplingPos).getBlock().getName().getString();
				fail("The server rejected every sapling at " + label(spot) + " (the block above is now "
					+ actual + "). No click was accepted; this is a server-side cancel (anti-cheat, "
					+ "claim/build permission, or a ghost support block), not a missed click.");
				return;
			}
		}

		// Sweep clockwise around the player instead of jumping between the
		// farthest-first scan order.  The camera only turns a few degrees to
		// the next pad, so a full 32-spot planting pass is one fast rotation.
		PlantSpot target = nearestByYaw(SpotState.EMPTY, true);
		if (target == null) {
			if (active.stream().anyMatch(spot -> spot.state == SpotState.EMPTY)) {
				status = "Waiting to retry planting";
				cooldown = 1;
			} else {
				fail("No empty planting spot and not all spots are planted.");
			}
			return;
		}

		if (saplings() <= 0) {
			fail("Out of spruce saplings.");
			return;
		}

		BlockHitResult hit = InteractionHelper.raycastTop(client, target.grassPos);
		if (hit == null && config.directFallback) {
			hit = InteractionHelper.directHitNear(client, target.grassPos, Direction.UP);
		}
		if (hit == null) {
			BlockHitResult blocker = InteractionHelper.blockingHit(client, target.grassPos);
			// The diagonal sapling of this 2x2 pad was planted a moment ago
			// and its cross can cover every sampled ray, even though a sapling
			// has no collision shape and cannot stop a placement.  Click the
			// soil top face through it instead of reporting the spot blind.
			hit = InteractionHelper.plantHitThroughPlants(client, target.grassPos, config.interactThroughSigns);
			if (hit == null) {
				target.visionFailures++;
				target.nextAttemptTick = ticks + 10;
				if (target.visionFailures > 8) {
					if (!InteractionHelper.canInteract(client, target.grassPos)) {
						fail("Planting spot at " + label(target)
							+ " is outside the server's interaction range. Stand closer to the centre.");
					} else {
						fail("Cannot see the planting spot at " + label(target) + blockingSuffix(blocker)
							+ ". Strict mode only uses visible interactions; enable directFallback in sprucefarm.json only if your server allows it.");
					}
				}
				return;
			}
		}

		InteractionHelper.EnsureResult ensure = InteractionHelper.ensureItem(
			client, stack -> stack.is(Items.SPRUCE_SAPLING), config
		);
		if (ensure.state() == InteractionHelper.Ensure.MISSING) {
			fail("Out of spruce saplings.");
			return;
		}
		if (ensure.state() == InteractionHelper.Ensure.BUSY) {
			// An inventory click is only predicted locally.  Wait for the
			// server's slot/state acknowledgement before using the item,
			// otherwise the placement can be sent with a stale hotbar slot.
			cooldown = guiDelay();
			status = ensure.detail();
			return;
		}

		queueAction(PendingType.PLANT, target, hit);
	}

	private void tickGrow() {
		refreshAllSpots();
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}

		PlantSpot blocked = firstSpot(SpotState.BLOCKED);
		if (blocked != null) {
			if (tryClearSpotObstruction(blocked, player)) {
				return;
			}
			if (waitForObstruction(blocked, "Growing")) {
				return;
			}
		}
		blockedWait = 0;
		List<PlantSpot> active = activeSpots();
		if (active.isEmpty()) {
			fail("Every pad was skipped after a failed fell. Restart or rescan to retry.");
			return;
		}
		if (firstSpot(SpotState.EMPTY) != null) {
			fail("A sapling disappeared during growth. Restart the cycle.");
			return;
		}
		if (handleGrowLeftovers(player)) {
			return;
		}
		if (active.stream().allMatch(spot -> spot.state == SpotState.GROWN)) {
			stage = Stage.HARVEST;
			harvestCursor = 0;
			status = "All trees grown. Harvesting.";
			return;
		}
		if (bonemeal() <= 0) {
			beginRestock(Stage.GROW);
			return;
		}

		for (PlantSpot spot : active) {
			if (spot.state == SpotState.PLANTED && spot.growAttempts >= config.maxBonemealPerSapling) {
				fail("Sapling at " + label(spot) + " did not grow after " + spot.growAttempts
					+ " bonemeal applications. " + growObstruction(spot));
				return;
			}
		}

		// Rotate the bonemeal across the planted spots.  Alternating the
		// applications lets every sapling advance a stage instead of leaving
		// one sapling hidden behind its neighbour's new tree.  Fast mode
		// snaps to each target, so the whole sweep is quick.
		PlantSpot target = null;
		int size = active.size();
		for (int offset = 0; offset < size; offset++) {
			PlantSpot spot = active.get((growCursor + offset) % size);
			if (spot.state != SpotState.PLANTED) {
				continue;
			}
			if (spot.growAttempts >= config.maxBonemealPerSapling) {
				fail("Sapling at " + label(spot) + " did not grow after " + spot.growAttempts
					+ " bonemeal applications. " + growObstruction(spot));
				return;
			}
			if (spot.nextAttemptTick <= ticks) {
				target = spot;
				growCursor = (growCursor + offset + 1) % size;
				break;
			}
		}
		if (target == null) {
			status = "Waiting to apply more bonemeal";
			cooldown = 1;
			return;
		}

		BlockHitResult hit = InteractionHelper.raycastBlock(client, target.saplingPos);
		if (hit == null && config.interactThroughSigns) {
			hit = InteractionHelper.signBlockedHit(client, target.saplingPos);
		}
		if (hit == null && config.directFallback) {
			hit = InteractionHelper.directHitNear(client, target.saplingPos, Direction.UP);
		}
		if (hit == null) {
			BlockHitResult blocker = InteractionHelper.blockingHit(client, target.saplingPos);
			// A sapling has no collision shape.  On a 2x2 pad its cross can
			// still cover the next sapling from the centre of the farm, which
			// is impossible to click with a strict outline ray but is not a
			// wall.  Click through the plant instead of stalling the cycle.
			if (blocker != null && client.level != null
				&& client.level.getBlockState(blocker.getBlockPos()).is(Blocks.SPRUCE_SAPLING)) {
				// Use the near edge, not the centre: an outer sapling can be
				// 4.5 blocks from the eye even though its near face is in reach.
				hit = InteractionHelper.directHitNear(client, target.saplingPos, Direction.UP);
			}
			if (hit == null) {
				target.visionFailures++;
				target.nextAttemptTick = ticks + 20;
				if (target.visionFailures > 10) {
					if (!InteractionHelper.canInteract(client, target.saplingPos)) {
						fail("Sapling at " + label(target)
							+ " is outside the server's interaction range. Stand closer to the centre.");
					} else {
						fail("Cannot see the sapling at " + label(target) + blockingSuffix(blocker)
							+ ". Strict mode only uses visible interactions; enable directFallback in sprucefarm.json only if your server allows it.");
					}
				}
				return;
			}
		}

		InteractionHelper.EnsureResult ensure = InteractionHelper.ensureItem(
			client, stack -> stack.is(Items.BONE_MEAL), config
		);
		if (ensure.state() == InteractionHelper.Ensure.MISSING) {
			beginRestock(Stage.GROW);
			return;
		}
		if (ensure.state() == InteractionHelper.Ensure.BUSY) {
			// An inventory click is only predicted locally.  Wait for the
			// server's slot/state acknowledgement before using the item,
			// otherwise the placement can be sent with a stale hotbar slot.
			cooldown = guiDelay();
			status = ensure.detail();
			return;
		}

		queueAction(PendingType.GROW, target, hit);
	}

	private void tickHarvest() {
		refreshAllSpots();
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}

		PlantSpot blocked = firstSpot(SpotState.BLOCKED);
		if (blocked != null) {
			if (tryClearSpotObstruction(blocked, player)) {
				return;
			}
			if (waitForObstruction(blocked, "Harvest")) {
				return;
			}
		}
		blockedWait = 0;
		if (firstSpot(SpotState.PLANTED) != null) {
			fail("A sapling is still present during harvest. Restart the cycle.");
			return;
		}

		if (firstSpot(SpotState.GROWN) == null) {
			// Every base log is gone, so the next cycle starts on this tick.
			// A slow Shard Axe fell may still be removing logs above the fresh
			// saplings; GROW clears those after planting instead of idling
			// here between cycles.  The water stream keeps delivering the old
			// drops to the stationary player during the new cycle.
			leftoverVision = 0;
			leftoverWait = 0;
			lastLeftoverCount = -1;
			leftoverQuiet = 0;
			finishCycle();
			return;
		}

		if (inventoryNeedsSell(player)) {
			beginTask(new SellTask(this));
			status = "Inventory nearly full: selling before continuing";
			return;
		}

		if (!hasAxe()) {
			fail("The axe is no longer in the inventory.");
			return;
		}

		// Harvest the nearest bottom log first.  Adjacent trunks are full
		// blocks and can hide the next one, so clearing the nearest tree opens
		// the line of sight to the ones behind it.  Fast mode breaks every
		// tick, so this still clears the farm in seconds.
		PlantSpot target = null;
		double bestDistance = Double.MAX_VALUE;
		Vec3 eye = player.getEyePosition();
		for (PlantSpot spot : spots) {
			if (spot.abandoned || spot.state != SpotState.GROWN || spot.nextAttemptTick > ticks) {
				continue;
			}
			double distance = eye.distanceToSqr(Vec3.atCenterOf(spot.saplingPos));
			if (distance < bestDistance) {
				target = spot;
				bestDistance = distance;
			}
		}
		if (target == null) {
			status = "Waiting to continue harvesting";
			cooldown = 1;
			return;
		}

		InteractionHelper.EnsureResult ensure = InteractionHelper.ensureItem(
			client, stack -> InventoryHelper.isAxe(stack, config), config
		);
		if (ensure.state() == InteractionHelper.Ensure.MISSING) {
			fail("The axe is no longer in the inventory.");
			return;
		}
		if (ensure.state() == InteractionHelper.Ensure.BUSY) {
			// An inventory click is only predicted locally.  Wait for the
			// server's slot/state acknowledgement before using the item,
			// otherwise the placement can be sent with a stale hotbar slot.
			cooldown = guiDelay();
			status = ensure.detail();
			return;
		}

		BlockHitResult hit = InteractionHelper.raycastBlock(client, target.saplingPos);
		if (hit == null && config.interactThroughSigns) {
			hit = InteractionHelper.signBlockedHit(client, target.saplingPos);
		}
		if (hit == null && config.directFallback) {
			hit = InteractionHelper.directHitNear(client, target.saplingPos, Direction.UP);
		}
		if (hit == null) {
			{
				BlockHitResult blocker = InteractionHelper.blockingHit(client, target.saplingPos);
				target.visionFailures++;
				target.nextAttemptTick = ticks + 20;
				if (target.visionFailures > 8) {
					if (!InteractionHelper.canInteract(client, target.saplingPos)) {
						fail("Bottom log at " + label(target)
							+ " is outside the server's interaction range. Stand closer to the centre.");
					} else {
						fail("Cannot see the bottom log at " + label(target) + blockingSuffix(blocker)
							+ ". Strict mode only uses visible interactions; enable directFallback in sprucefarm.json only if your server allows it.");
					}
				}
				return;
			}
		}

		queueAction(PendingType.HARVEST, target, hit);
	}

	/**
	 * Gives up on a tree the axe cannot finish from the fixed position.  The
	 * whole pad is skipped so a floating trunk never stops the rest of the
	 * farm; the logs stay where they are, which the user explicitly accepts.
	 */
	private void abandonTree(BlockPos lowest, String reason) {
		int remaining = countLeftoverLogs();
		PlantSpot spot = nearestSpot(lowest);
		if (spot != null) {
			for (PlantSpot other : spots) {
				if (other.padId == spot.padId) {
					other.abandoned = true;
					other.visionFailures = 0;
					other.nextAttemptTick = 0;
				}
			}
		}
		cycleStats.skippedTrees++;
		totalStats.skippedTrees++;
		leftoverVision = 0;
		leftoverWait = 0;
		leftoverQuiet = 0;
		lastLeftoverCount = -1;
		fellWaitBudget = config.fellFinishTimeoutTicks;
		cooldown = 2;
		status = "Skipping a tree the axe could not finish. Continuing.";
		LOGGER.warn("Skipping a floating tree at {} (pad {}): {}. {} log(s) remain; the farm continues.",
			pos(lowest), spot == null ? -1 : spot.padId, reason, remaining);
	}

	/**
	 * Clears a trunk the server's fell left above a fresh sapling.  Since the
	 * harvest starts the next cycle immediately, this runs in GROW before any
	 * bonemeal: the new saplings are already planted and any log above one can
	 * only be left over from the previous tree.  A gradual fell is still given
	 * its quiet window here, just in parallel with planting instead of as a
	 * pause between cycles.
	 *
	 * @return true when the grow stage should end this tick
	 */
	private boolean handleGrowLeftovers(LocalPlayer player) {
		BlockPos leftover = firstLeftoverTrunk();
		if (leftover == null) {
			leftoverVision = 0;
			leftoverWait = 0;
			leftoverQuiet = 0;
			lastLeftoverCount = -1;
			fellWaitBudget = config.fellFinishTimeoutTicks;
			return false;
		}
		int remaining = countLeftoverLogs();
		if (leftoverWait > 0) {
			leftoverWait--;
			status = "Waiting for the Shard Axe to finish (" + remaining + " logs left)";
			cooldown = 1;
			return true;
		}
		if (lastLeftoverCount < 0 || remaining < lastLeftoverCount) {
			lastLeftoverCount = remaining;
			leftoverQuiet = 0;
			leftoverWait = harvestSettle();
			// Progress resets the quiet window: a slow but working fell is
			// never mistaken for a finished one, while a stopped fell still
			// gets only the configured grace period.
			fellWaitBudget = config.fellFinishTimeoutTicks;
			status = "Waiting for the Shard Axe to finish (" + remaining + " logs left)";
			cooldown = 1;
			return true;
		}
		// No change for a whole quiet window means the plugin's fell really
		// has stopped.  A single sample can be a delay before its next block
		// removal, and intervening then would fight the ongoing fell.
		leftoverQuiet += harvestSettle();
		if (leftoverQuiet < config.fellQuietTicks) {
			leftoverWait = harvestSettle();
			fellWaitBudget -= harvestSettle();
			status = "Waiting for the Shard Axe to finish (" + remaining + " logs left)";
			cooldown = 1;
			return true;
		}
		if (fellWaitBudget <= 0) {
			abandonTree(leftover, "the server fell stopped with " + remaining + " logs left");
			return true;
		}
		handleLeftoverLog(leftover, player);
		return true;
	}

	/**
	 * The Shard Axe fells the connected tree above the log it is applied to.
	 * When it stops before the trunk is gone (a fell limit, a late server
	 * update, or a tree shape the plugin did not follow), break the new lowest
	 * remaining log from the same position.  Each extra fell removes the next
	 * segment of the same tree, so the player never has to move or jump.
	 */
	private void handleLeftoverLog(BlockPos leftover, LocalPlayer player) {			int remaining = countLeftoverLogs();
	if (cycleStats.extraFells >= config.maxHarvestPasses) {
		abandonTree(leftover, "the axe's fell limit was reached after "
			+ cycleStats.extraFells + " extra fell(s)");
		return;
	}
		if (!InteractionHelper.canInteract(client, leftover)) {
			abandonTree(leftover, "the lowest remaining log is above the 4.5-block reach");
			return;
		}
		BlockHitResult hit = InteractionHelper.raycastBlock(client, leftover);
		if (hit == null && config.interactThroughSigns) {
			hit = InteractionHelper.signBlockedHit(client, leftover);
		}
		if (hit == null && config.directFallback) {
			hit = InteractionHelper.directHitNear(client, leftover, Direction.UP);
		}
		if (hit == null) {
			BlockHitResult blocker = InteractionHelper.blockingHit(client, leftover);
			leftoverVision++;
			if (leftoverVision > 12) {
				abandonTree(leftover, "the remaining log is hidden" + blockingSuffix(blocker));
				return;
			}
			status = "Waiting to see the remaining log at " + pos(leftover);
			cooldown = 2;
			return;
		}
		leftoverVision = 0;
		LOGGER.info("Finishing a fell-limited tree: {} logs remain, breaking the new bottom at {} (pass {})",
			remaining, pos(leftover), cycleStats.extraFells + 1);
		queueAction(PendingType.HARVEST_LEFTOVER, nearestSpot(leftover), hit);
	}

	private PlantSpot nearestSpot(BlockPos pos) {
		PlantSpot best = null;
		double bestDistance = Double.MAX_VALUE;
		for (PlantSpot spot : spots) {
			double distance = spot.saplingPos.distSqr(pos);
			if (distance < bestDistance) {
				best = spot;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** Starts clearing tall grass, a flower or another replaceable block on a spot. */
	private boolean tryClearSpotObstruction(PlantSpot spot, LocalPlayer player) {
		if (client.level == null) {
			return false;
		}
		BlockState state = client.level.getBlockState(spot.saplingPos);
		if (!isClearableObstruction(state) || spot.obstructionClears >= 24) {
			return false;
		}
		InteractionHelper.EnsureResult axe = InteractionHelper.ensureItem(
			client, stack -> InventoryHelper.isAxe(stack, config), config
		);
		if (axe.state() == InteractionHelper.Ensure.MISSING) {
			fail("The axe is no longer in the inventory.");
			return true;
		}
		if (axe.state() == InteractionHelper.Ensure.BUSY) {
			cooldown = guiDelay();
			status = axe.detail();
			return true;
		}
		queueAction(PendingType.CLEAR_OBSTRUCTION, spot,
			new BlockHitResult(Vec3.atCenterOf(spot.saplingPos), Direction.UP, spot.saplingPos, false));
		return true;
	}

	private boolean isClearableObstruction(BlockState state) {
		return state.canBeReplaced() && state.getFluidState().isEmpty();
	}

	private String blockingSuffix(BlockHitResult blocker) {
		if (blocker == null || client.level == null) {
			return "";
		}
		BlockState state = client.level.getBlockState(blocker.getBlockPos());
		return " (blocked by " + state.getBlock().getName().getString()
			+ " at " + pos(blocker.getBlockPos()) + ")";
	}

	/**
	 * Drives an in-progress block break for every stage.  The harvest stage
	 * verifies on its next free tick that the trunk columns are clear.
	 */
	private boolean tickBreaking() {
		if (breakPos == null || client.level == null) {
			breaking = false;
			breakingObstruction = false;
			InteractionHelper.stopBreaking(client);
			return true;
		}
		BlockState current = client.level.getBlockState(breakPos);
		if (breakingObstruction) {
			if (current.isAir() || !isClearableObstruction(current)) {
				breaking = false;
				breakingObstruction = false;
				InteractionHelper.stopBreaking(client);
				cooldown = 1;
				return true;
			}
			if (breakTicks++ > 40) {
				breaking = false;
				breakingObstruction = false;
				InteractionHelper.stopBreaking(client);
				fail("Could not clear the obstruction at " + pos(breakPos) + ".");
				return true;
			}
			InteractionHelper.continueBreaking(client, breakPos);
			cooldown = breakDelay();
			status = "Clearing obstruction at " + pos(breakPos);
			return true;
		}

		if (current.isAir() || !isSpruceLog(current)) {
			boolean extra = breakingLeftover;
			breaking = false;
			breakingLeftover = false;
			if (!extra) {
				// A new tree fell: give its server-side fell a fresh budget.
				lastLeftoverCount = -1;
				leftoverWait = 0;
				leftoverQuiet = 0;
				fellWaitBudget = config.fellFinishTimeoutTicks;
			}
			InteractionHelper.stopBreaking(client);
			// Check the trunk columns immediately.  While the server's fell
			// is still removing logs the quiet window in tickHarvest keeps
			// waiting, so an instant fell restarts the cycle without a fixed
			// verification pause.
			status = extra ? "Verifying the extra fell" : "Verifying harvest";
			cooldown = 1;
			return true;
		}
		if (breakTicks++ > 60) {
			breaking = false;
			InteractionHelper.stopBreaking(client);
			if (breakingLeftover) {
				BlockPos stuck = breakPos;
				breakingLeftover = false;
				abandonTree(stuck, "the remaining log could not be broken");
			} else {
				fail("Could not break the log at " + pos(breakPos) + ". Is the axe equipped?");
			}
			return true;
		}
		InteractionHelper.continueBreaking(client, breakPos);
		cooldown = breakDelay();
		status = (breakingLeftover ? "Breaking remaining log at " : "Breaking tree base at ") + pos(breakPos);
		return true;
	}

	/**
	 * Ends a harvest.  The sell GUI is opened only when the inventory can no
	 * longer take another cycle; otherwise the next cycle starts immediately.
	 * Its drops keep flowing to the player while that cycle runs.
	 */
	private void finishCycle() {
		LocalPlayer player = client.player;
		if (player == null) {
			return;
		}
		if (inventoryNeedsSell(player)) {
			stage = Stage.SELL;
			status = "Inventory nearly full: selling before the next cycle";
			return;
		}
		beginCycle();
	}

	private void afterSell() {
		if (client.player == null) {
			return;
		}
		if (bonemeal() > 0) {
			beginCycle();
		} else {
			beginRestock(Stage.PLANT);
		}
	}

	private void beginRestock(Stage resume) {
		if (client.player == null) {
			return;
		}
		if (chests.isEmpty()) {
			rescanChests();
		}
		if (chests.isEmpty()) {
			fail("No chests detected within reach under the player.");
			return;
		}
		restockResume = resume;
		stage = Stage.RESTOCK;
		status = "Restocking bonemeal";
		if (task == null) {
			beginTask(new RestockTask(this, chests, chestHint));
		}
	}

	private void beginTask(FarmTask newTask) {
		pending = null;
		if (breaking) {
			// Opening a container cancels the break on the server; stop it
			// locally as well so the task starts from a clean state.
			InteractionHelper.stopBreaking(client);
			breaking = false;
			breakingObstruction = false;
			breakingLeftover = false;
		}
		if (client.screen instanceof FarmScreen) {
			reopenPanel = true;
			client.setScreen(null);
		}
		task = newTask;
	}

	private void tickTask() {
		FarmTask.Status result = task.tick();
		if (result == FarmTask.Status.RUNNING) {
			status = task.status();
			return;
		}
		FarmTask finished = task;
		task = null;
		if (result == FarmTask.Status.FAILED) {
			String message = finished.error();
			finished.cancel();
			reopenPanelIfNeeded();
			fail(message == null || message.isEmpty() ? "Task failed" : message);
			return;
		}
		reopenPanelIfNeeded();
		// Give the server a moment after the container closes before the next
		// world interaction.  Sell/restock plugins process the close packet,
		// and servers such as DonutSMP reject actions that arrive before their
		// menu state has caught up.  Four ticks is enough for that handshake
		// and saves half a second on every sell/restock.
		cooldown = Math.max(cooldown, 4);
		if (stage == Stage.SELL) {
			afterSell();
		} else if (stage == Stage.RESTOCK) {
			if (bonemeal() <= 0) {
				fail("No bonemeal left in either chest. Farming paused.");
				return;
			}
			if (restockResume == Stage.PLANT) {
				beginCycle();
			} else {
				stage = restockResume;
				status = "Bonemeal restocked. Continuing.";
			}
		} else if (paused) {
			status = "Paused";
		}
	}

	private void reopenPanelIfNeeded() {
		if (reopenPanel) {
			reopenPanel = false;
			if (client.screen == null) {
				client.setScreen(new FarmScreen(this));
			}
		}
	}

	private void refreshAllSpots() {
		for (PlantSpot spot : spots) {
			SpotState before = spot.state;
			boolean wasConfirmed = spot.serverConfirmedPlant;
			SpotState now = liveState(spot);

			// A placement is only counted once the server sends the block
			// update for the clicked block or the sapling itself.  Until then
			// the client's state is a prediction and may be rolled back.
			if (spot.plantClickSequence >= 0) {
				long serverSeq = ServerState.blockSequence(spot.saplingPos);
				if (serverSeq > spot.plantClickSequence) {
					BlockState server = ServerState.block(spot.saplingPos);
					spot.plantClickSequence = -1;
					if (server != null && server.is(Blocks.SPRUCE_SAPLING)) {
						spot.serverConfirmedPlant = true;
						spot.serverRejectPending = false;
						now = SpotState.PLANTED;
					} else {
						String actual = server == null ? "unknown" : server.getBlock().getName().getString();
						spot.serverConfirmedPlant = false;
						spot.serverRejections++;
						spot.serverRejectPending = true;
						spot.note = actual;
						now = server == null ? now : (server.isAir() ? SpotState.EMPTY : SpotState.BLOCKED);
						LOGGER.warn("Server rolled back the sapling at {} (block above is {}, clicked block is {}, hit {} at {} blocks, attempt {}, rejection {})",
							label(spot), actual, clickedName(spot), spot.lastHitFace,
							String.format("%.2f", spot.lastHitDistance), spot.plantAttempts,
							spot.serverRejections);
						if (spot.hidden && config.skipHiddenSpots) {
							// This click needed a line of sight through the sign box.
							// The server has just said no; retrying cannot make the
							// signs transparent, and every retry is another cancelled
							// prediction (the ghost-sapling stream).  Skip the spots
							// that need that click and keep the visible farm running.
							spot.state = now;
							abandonHiddenSpots("the server rolled back a placement through the sign box");
							return;
						}
						// Do not click the same spot again and again after the server
						// has explicitly sent the block back as something other than
						// a sapling.  Each retry is a fresh prediction that the
						// server cancels, which is exactly the stream of instant
						// ghost saplings.  One retry covers a lost/racing update;
						// a second rejection is a server-side refusal that this
						// client cannot fix by clicking harder.
						if (spot.serverRejections >= 2) {
							spot.state = now;
							if (config.skipHiddenSpots) {
								abandonRejectedSpot(spot, "the server refused the sapling twice");
								return;
							}
							fail("The server refused the sapling at " + label(spot) + " twice (server says "
								+ actual + " above the " + clickedName(spot) + "; last hit "
								+ spot.lastHitFace + " at " + String.format("%.2f", spot.lastHitDistance)
								+ " blocks). This is a server-side cancel: anti-cheat, claim/build permission, "
								+ "or a ghost support block. Test a manual placement at this exact spot.");
							return;
						}
						spot.nextAttemptTick = ticks + 40 + Math.min(60, spot.serverRejections * 10);
					}
				} else if (ticks - spot.plantClickTick > 60) {
					spot.plantClickSequence = -1;
					if (spot.hidden && config.skipHiddenSpots) {
						// A strict server can drop a covered click without
						// sending a block rollback.  Do not wait forever.
						spot.state = now;
						abandonHiddenSpots("the server never confirmed a placement through the sign box");
						return;
					}
					fail("The server did not confirm the sapling at " + label(spot)
						+ ". Move slightly and press Rescan.");
					return;
				}
			}

			if (spot.serverRejectPending) {
				// The server has already sent its real block state, but the
				// client keeps the predicted sapling until the matching
				// block-change acknowledgement.  Do not click this spot while
				// that ghost is still in the local world.
				if (now == SpotState.PLANTED) {
					now = SpotState.EMPTY;
				} else {
					spot.serverRejectPending = false;
				}
			} else if (spot.plantClickSequence < 0 && !spot.serverConfirmedPlant
				&& now == SpotState.PLANTED) {
				// Saplings that were already standing when the cycle started
				// came from the server chunk, not from a local prediction.
				spot.serverConfirmedPlant = true;
			}

			if (stage == Stage.PLANT && !wasConfirmed && spot.serverConfirmedPlant) {
				cycleStats.planted++;
				totalStats.planted++;
			}
			if (stage == Stage.GROW && before != SpotState.GROWN && now == SpotState.GROWN) {
				cycleStats.grown++;
				totalStats.grown++;
			}
			if (stage == Stage.HARVEST && before == SpotState.GROWN && now == SpotState.EMPTY) {
				cycleStats.harvested++;
				totalStats.harvested++;
			}
			spot.state = now;
		}
	}

	private SpotState liveState(PlantSpot spot) {
		ClientLevel level = client.level;
		if (level == null) {
			return spot.state;
		}
		BlockState soil = level.getBlockState(spot.grassPos);
		// Trees convert grass to dirt, and saplings can be planted on any
		// block in the dirt tag, so keep the spot valid in both states.
		if (!soil.is(BlockTags.DIRT)) {
			spot.note = "no soil block";
			return SpotState.BLOCKED;
		}
		BlockState above = level.getBlockState(spot.saplingPos);
		if (above.isAir()) {
			spot.note = "";
			return SpotState.EMPTY;
		}
		if (above.is(Blocks.SPRUCE_SAPLING)) {
			spot.note = "";
			return SpotState.PLANTED;
		}
		if (above.is(Blocks.SPRUCE_LOG)) {
			spot.note = "";
			return SpotState.GROWN;
		}
		spot.note = above.getBlock().getName().getString();
		return SpotState.BLOCKED;
	}

	private void rescanLayout() {
		FarmLayout.ScanResult result = FarmLayout.scan(client, config);
		Map<BlockPos, PlantSpot> old = new HashMap<>();
		for (PlantSpot spot : spots) {
			old.put(spot.grassPos, spot);
		}
		spots.clear();
		for (PlantSpot spot : result.spots()) {
			PlantSpot previous = old.get(spot.grassPos);
			if (previous != null) {
				spot.plantAttempts = previous.plantAttempts;
				spot.growAttempts = previous.growAttempts;
				spot.visionFailures = previous.visionFailures;
				spot.nextAttemptTick = previous.nextAttemptTick;
				spot.plantClickSequence = previous.plantClickSequence;
				spot.plantClickTick = previous.plantClickTick;
				spot.serverConfirmedPlant = previous.serverConfirmedPlant;
				spot.serverRejections = previous.serverRejections;
				spot.serverRejectPending = previous.serverRejectPending;
			}
			spots.add(spot);
		}
		pads = result.pads();
		blockedSpots = result.blocked();
		unreachableSpots = result.unreachable();
		if (growCursor >= spots.size()) {
			growCursor = 0;
		}
		if (harvestCursor >= spots.size()) {
			harvestCursor = 0;
		}
	}

	private void rescanChests() {
		// A rescan deliberately forgets cached stock: the player may have
		// refilled a chest since it was last opened, and a stale zero would
		// otherwise block farming forever.
		chests = FarmLayout.findChests(client, config);
	}

	private boolean hasAxe() {
		return client.player != null && InventoryHelper.findAxe(client.player, config) >= 0;
	}

	private int saplings() {
		return client.player == null ? 0 : InventoryHelper.countSaplings(client.player);
	}

	private int bonemeal() {
		if (client.player == null) {
			return 0;
		}
		// The mirror is kept in step with our own clicks and corrected by every
		// packet the server sends, so it is the only count that notices items
		// removed by something other than this mod (an admin /clear, or a click
		// the server rejected and rolled back).
		if (ServerState.inventoryKnown()) {
			return ServerState.count(Items.BONE_MEAL);
		}
		return InventoryHelper.countBonemeal(client.player);
	}

	/**
	 * True only when the inventory can no longer absorb another harvest and
	 * there is something worth selling.  Selling then pauses farming for a
	 * few seconds; otherwise the farm keeps running through cycle after cycle
	 * and never opens the sell GUI.
	 */
	private boolean inventoryNeedsSell(LocalPlayer player) {
		if (player == null) {
			return false;
		}
		int sellable = InventoryHelper.sellableCount(player, config.keepSaplings);
		return com.sprucefarm.logic.SellPlanner.needsSale(
			InventoryHelper.freeSlots(player), config.sellFreeSlots, sellable
		);
	}

	/**
	 * Falling blocks, crop drops and other transient obstructions are allowed
	 * to clear themselves before the cycle is paused.
	 */
	private boolean waitForObstruction(PlantSpot spot, String action) {
		blockedWait++;
		if (blockedWait > 80) {
			fail(action + " stopped: spot " + label(spot) + " is blocked by " + spot.note + ".");
			return true;
		}
		status = "Waiting for obstruction at " + label(spot) + " (" + spot.note + ")";
		cooldown = 4;
		return true;
	}

	private PlantSpot firstSpot(SpotState state) {
		for (PlantSpot spot : spots) {
			if (!spot.abandoned && spot.state == state) {
				return spot;
			}
		}
		return null;
	}

	/** Spots that still belong to the farm; a skipped pad is excluded. */
	private List<PlantSpot> activeSpots() {
		return spots.stream().filter(spot -> !spot.abandoned).toList();
	}

	/**
	 * Flags the empty spots whose top face the camera cannot see from the
	 * centre.  With a sign box these are the spots behind the upper boards:
	 * a direct click is impossible, so only a through-sign click could reach
	 * them.  Computing this before the first click lets a strict server be
	 * detected on one rejection instead of after a long stream of ghost
	 * saplings.
	 */
	private void markHiddenSpots() {
		for (PlantSpot spot : spots) {
			if (spot.abandoned) {
				continue;
			}
			spot.hidden = spot.state == SpotState.EMPTY
				&& InteractionHelper.raycastTop(client, spot.grassPos) == null;
		}
	}

	/**
	 * Drops one spot the server keeps refusing even though the scan saw its
	 * top face.  This is the single-spot counterpart of
	 * {@link #abandonHiddenSpots(String)}: a claim, a neighbour's block or a
	 * ghost support block should cost one spot, not the whole farm.
	 */
	private void abandonRejectedSpot(PlantSpot spot, String reason) {
		if (spot.abandoned) {
			return;
		}
		spot.abandoned = true;
		int active = activeSpots().size();
		warning = "Skipped " + (spots.size() - active) + "/" + spots.size()
			+ " spots the server refused; continuing with " + active
			+ ". Fix or move the spot, then press Rescan.";
		LOGGER.warn("Skipping {} ({}): the server refused it; continuing with {} spots.",
			label(spot), reason, active);
	}

	/**
	 * A sign-covered placement was refused.  There is nothing this client can
	 * do differently: the server checks the line of sight, not the click
	 * vector.  Drop every spot that needs that impossible line, tell the user
	 * exactly what to remove, and keep the visible spots running.
	 */
	private void abandonHiddenSpots(String reason) {
		int skipped = 0;
		for (PlantSpot spot : spots) {
			if (spot.hidden && !spot.abandoned) {
				spot.abandoned = true;
				skipped++;
			}
		}
		if (skipped == 0) {
			return;
		}
		skippedHidden += skipped;
		warning = "Skipped " + skippedHidden + "/" + spots.size()
			+ " spots behind the sign box; remove the top sign layer and press Rescan. Continuing with "
			+ activeSpots().size() + ".";
		LOGGER.warn("Skipping {} hidden spots ({}): remove the upper sign layer and rescan for the full farm.",
			skipped, reason);
	}

	/**
	 * The lowest spruce log left above a freshly planted sapling, or null when
	 * every new sapling has clear space.  Only PLANTED spots count: a GROWN
	 * spot's logs are its own new tree, not a leftover.  Returning the lowest
	 * log is deliberate: a fell-limited axe removes the connected tree above
	 * the log it is applied to, so chopping the new bottom finishes the same
	 * tree from the same spot.
	 */
	private BlockPos firstLeftoverTrunk() {
		ClientLevel level = client.level;
		if (level == null) {
			return null;
		}
		BlockPos lowest = null;
		for (PlantSpot spot : spots) {
			if (spot.abandoned || spot.state != SpotState.PLANTED) {
				continue;
			}
			// Large spruces can be about 30 blocks tall; check the full column
			// so a partially felled tree cannot be mistaken for a finished one.
			for (int dy = 1; dy <= 32; dy++) {
				BlockPos candidate = spot.saplingPos.above(dy);
				if (level.getBlockState(candidate).is(Blocks.SPRUCE_LOG)
					&& (lowest == null || candidate.getY() < lowest.getY())) {
					lowest = candidate;
				}
			}
		}
		return lowest;
	}

	private int countLeftoverLogs() {
		ClientLevel level = client.level;
		if (level == null) {
			return 0;
		}
		int total = 0;
		for (PlantSpot spot : spots) {
			if (spot.abandoned || spot.state != SpotState.PLANTED) {
				continue;
			}
			for (int dy = 1; dy <= 32; dy++) {
				if (level.getBlockState(spot.saplingPos.above(dy)).is(Blocks.SPRUCE_LOG)) {
					total++;
				}
			}
		}
		return total;
	}

	private boolean isSpruceLog(BlockState state) {
		return state.is(Blocks.SPRUCE_LOG);
	}

	private int actionDelay() {
		if (config.fastMode) {
			// One action per tick is the vanilla limit.  The previous tick's
			// turn has already reached the server by then, so the next target
			// is aimed right after a click and the click lands one tick later:
			// twenty actions per second without a look and a click in the same
			// tick.
			return 1;
		}
		return config.actionDelayTicks + random.nextInt(3);
	}

	/** Breaking continues every tick in fast mode, like holding the mouse. */
	private int breakDelay() {
		return config.fastMode ? 0 : actionDelay();
	}

	/** Inventory and vision waits; fast mode only shortens the safe parts. */
	private int guiDelay() {
		return config.fastMode ? 2 : config.guiDelayTicks;
	}

	private int harvestSettle() {
		// Poll interval while the server's fell still has logs to remove.  Fast
		// mode caps it so a still-running fell never stalls the next cycle for
		// long.
		return config.fastMode ? Math.min(8, config.harvestSettleTicks) : config.harvestSettleTicks;
	}

	/**
	 * Picks the next spot in the direction the camera is already facing.  With
	 * clockwise=true each target is the next pad around the circle, so the
	 * view sweeps once instead of jumping back and forth.
	 */
	private PlantSpot nearestByYaw(SpotState state, boolean clockwise) {
		LocalPlayer player = client.player;
		if (player == null) {
			return null;
		}
		Vec3 eye = player.getEyePosition();
		double currentYaw = player.getYRot();
		PlantSpot best = null;
		double bestDelta = Double.MAX_VALUE;
		for (PlantSpot spot : spots) {
			if (spot.abandoned || spot.state != state || spot.nextAttemptTick > ticks) {
				continue;
			}
			Vec3 centre = Vec3.atCenterOf(spot.saplingPos);
			float yaw = LookMath.yawTo(eye.x, eye.z, centre.x, centre.z);
			double delta = Mth.wrapDegrees(yaw - currentYaw);
			if (clockwise) {
				// Strictly ahead: the spot the camera already points at was
				// the one just handled, so skipping it keeps the sweep moving
				// around the ring instead of re-clicking the same block until
				// it grows.
				if (delta <= 0.05D) {
					delta += 360.0D;
				}
			} else {
				delta = Math.abs(delta);
			}
			if (delta < bestDelta) {
				best = spot;
				bestDelta = delta;
			}
		}
		return best;
	}

	private String growObstruction(PlantSpot spot) {
		ClientLevel level = client.level;
		if (level == null) {
			return "No world loaded.";
		}
		StringBuilder found = new StringBuilder();
		// Include the sapling's own level: a sign one block beside a 2x2
		// spruce sits in the mega tree's 3x3 trunk clearance even though it
		// is not above the sapling, and that is what rejects the shape.
		for (int dy = 0; dy <= 12; dy++) {
			for (int dx = -2; dx <= 2; dx++) {
				for (int dz = -2; dz <= 2; dz++) {
					BlockPos pos = spot.saplingPos.offset(dx, dy, dz);
					BlockState state = level.getBlockState(pos);
					if (state.isAir() || state.is(Blocks.SPRUCE_SAPLING)
						|| state.is(Blocks.SPRUCE_LOG) || state.is(BlockTags.LEAVES)) {
						continue;
					}
					if (found.length() > 0) {
						found.append(", ");
					}
					found.append(pos).append('=').append(state.getBlock().getName().getString());
					if (found.length() > 180) {
						return "Nearby blocks: " + found + "...";
					}
				}
			}
		}
		return found.length() == 0
			? "No blocking blocks within 12 blocks above; the server rejected the tree shape."
			: "Nearby blocks: " + found + ".";
	}

	private String label(PlantSpot spot) {
		return spot == null ? "the obstruction" : pos(spot.grassPos);
	}

	private String pos(BlockPos pos) {
		return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
	}

	private String clickedName(PlantSpot spot) {
		BlockState state = ServerState.block(spot.grassPos);
		if (state == null) {
			return "unknown";
		}
		return state.getBlock().getName().getString();
	}

	private void fail(String message) {
		if (task != null) {
			task.cancel();
			task = null;
		}
		if (breaking) {
			InteractionHelper.stopBreaking(client);
			breaking = false;
			breakingObstruction = false;
		}
		pending = null;
		stage = Stage.ERROR;
		paused = false;
		error = message;
		status = "Error";
		reopenPanel = false;
	}

	public String chestSummary() {
		if (chests.isEmpty()) {
			return "no chests detected";
		}
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < chests.size(); i++) {
			if (i > 0) {
				builder.append("  |  ");
			}
			builder.append("Chest ").append(i + 1).append(": ")
				.append(chests.get(i).stockLabel(ticks));
		}
		return builder.toString();
	}

	public int knownChestBonemeal() {
		int total = 0;
		for (ChestGroup group : chests) {
			if (group.cachedBonemeal > 0) {
				total += group.cachedBonemeal;
			}
		}
		return total;
	}
}
