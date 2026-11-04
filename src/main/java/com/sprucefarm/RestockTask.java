package com.sprucefarm;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Opens one of the two bonemeal chests, takes a single full stack and verifies
 * that it entered the player's inventory. Empty chests are skipped.
 */
public final class RestockTask implements FarmTask {
	private enum Step {
		OPEN,
		CLICK,
		WAIT_OPEN,
		TAKE,
		VERIFY,
		MANUAL_PICKUP,
		MANUAL_PLACE,
		CLOSE,
		NEXT,
		DONE,
		FAILED
	}

	private final FarmController controller;
	private final Minecraft client;
	private final FarmConfig config;
	private final LocalPlayer player;
	private final List<ChestGroup> groups = new ArrayList<>();

	private Step step = Step.OPEN;
	private ChestGroup current;
	private final Set<Long> tried = new HashSet<>();
	private int wait;
	private int timeout;
	private int taskTicks;
	private String error = "";

	private int beforeBonemeal;
	private int stableTicks;
	private int verifyTicks;
	private int verifyAttempts;
	private boolean manualTried;
	private int chestCount;
	private int sourceMenuSlot = -1;
	private int manualTarget = -1;
	private BlockHitResult pendingChestHit;
	private int openAttempts;
	private int openedChests;
	private int emptyReads;
	private int unsyncedChests;
	private Map<Integer, Long> contentBaseline = Map.of();
	private boolean contentSeen;
	private int contentWait;

	public RestockTask(FarmController controller, List<ChestGroup> source, long preferredKey) {
		this.controller = controller;
		this.client = controller.client();
		this.config = controller.config();
		this.player = client.player;
		groups.addAll(source);
		ChestGroup preferred = null;
		for (ChestGroup group : groups) {
			if (group.key == preferredKey) {
				preferred = group;
				break;
			}
		}
		if (preferred != null && preferred.cachedBonemeal != 0) {
			this.current = preferred;
		} else {
			// Avoid reopening a chest that was already found empty; an
			// unknown cache is worth checking.
			for (ChestGroup group : groups) {
				if (group.cachedBonemeal != 0) {
					this.current = group;
					break;
				}
			}
			if (this.current == null) {
				this.current = preferred != null ? preferred : (groups.isEmpty() ? null : groups.get(0));
			}
		}
	}

	@Override
	public Status tick() {
		if (player == null || client.level == null) {
			return fail("No player");
		}
		taskTicks++;
		if (taskTicks > 1200) {
			return fail("Chest restock timed out before any bonemeal could be taken");
		}

		switch (step) {
			case OPEN -> {
				if (current == null) {
					return nextChest();
				}
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				if (isContainerOpen()) {
					closeGui();
					wait = 4;
					return Status.RUNNING;
				}

				BlockHitResult hit = findChestHit();
				if (hit == null) {
					return nextChest();
				}

				// Any held item opens a chest, so do not spend ticks swapping to
				// the axe here; the next farming stage selects it when needed.
				// Look now and click on the following tick, using the crosshair's
				// real chest hit instead of a synthetic sample that a strict
				// server can reject.
				pendingChestHit = hit;
				InteractionHelper.lookNow(player, hit.getLocation());
				wait = 1;
				step = Step.CLICK;
			}
			case CLICK -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				if (isContainerOpen()) {
					openedChests++;
					emptyReads = 0;
					contentSeen = true;
					step = Step.TAKE;
					wait = contentDelay();
					return Status.RUNNING;
				}
				BlockHitResult camera = pendingChestHit == null ? null
					: InteractionHelper.cameraHit(client, pendingChestHit.getBlockPos());
				if (camera == null && current != null) {
					for (BlockPos pos : current.blocks) {
						camera = InteractionHelper.cameraHit(client, pos);
						if (camera != null) {
							break;
						}
					}
				}
				if (camera != null && client.level != null
					&& client.level.getBlockState(camera.getBlockPos()).getBlock() instanceof net.minecraft.world.level.block.ChestBlock) {
					pendingChestHit = camera;
				}
				if (pendingChestHit == null) {
					return nextChest();
				}
				contentBaseline = ServerState.containerContentSnapshot();
				contentSeen = false;
				contentWait = 0;
				if (!InteractionHelper.useOn(client, pendingChestHit).consumesAction()) {
					return nextChest();
				}
				wait = openDelay();
				timeout = openTimeout();
				step = Step.WAIT_OPEN;
			}
			case WAIT_OPEN -> {
				if (isContainerOpen()) {
					openedChests++;
					emptyReads = 0;
					step = Step.TAKE;
					wait = contentDelay();
					return Status.RUNNING;
				}
				if (--timeout <= 0) {
					closeGui();
					if (++openAttempts < 3) {
						// The click may have been sent a tick too early or the
						// server may have missed it; look again and retry before
						// giving up on this chest.
						step = Step.OPEN;
						wait = 1;
						timeout = openTimeout();
						return Status.RUNNING;
					}
					return nextChest();
				}
			}
			case TAKE -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				if (!isContainerOpen()) {
					return nextChest();
				}
				// An open chest screen can be visible a tick before the
				// server's contents packet.  Only trust a read after a real
				// content packet has arrived, so a lagging chest is never
				// cached as empty and abandoned for the rest of the session.
				AbstractContainerMenu menu = player.containerMenu;
				if (!contentSeen) {
					long before = contentBaseline.getOrDefault(menu.containerId, 0L);
					if (ServerState.containerContentCount(menu.containerId) > before) {
						contentSeen = true;
					}
				}
				if (!contentSeen) {
					// Two seconds covers a laggy server without ever treating a
					// not-yet-synced chest as empty.
					if (contentWait++ < 40) {
						wait = 1;
						return Status.RUNNING;
					}
					unsyncedChests++;
					closeGui();
					wait = 1;
					step = Step.NEXT;
					return Status.RUNNING;
				}

				chestCount = countContainerBonemeal(menu);
				current.cachedBonemeal = chestCount;
				current.lastCheckedTick = controller.tickCount();
				// Keep a record of what was actually read: a server that rejects
				// chest opens looks identical to an empty chest in the panel, but
				// not in the log.
				SpruceFarmClient.LOGGER.info("Chest {} read from the server: {} bonemeal across {} slots (container {})",
					pos(current), chestCount, menu.slots.size(), menu.containerId);

				if (chestCount <= 0) {
					// Contents are server-sent now; a second read one tick later
					// covers a slot update racing the content packet.
					if (emptyReads < 2) {
						emptyReads++;
						wait = 1;
						return Status.RUNNING;
					}
					current.empty = true;
					current.cachedBonemeal = 0;
					closeGui();
					wait = 2;
					step = Step.NEXT;
					return Status.RUNNING;
				}

				ItemStack bonemeal = new ItemStack(Items.BONE_MEAL);
				if (!InventoryHelper.hasRoomFor(player, bonemeal)) {
					closeGui();
					return fail("Inventory is full; cannot take bonemeal.");
				}

				sourceMenuSlot = findContainerSlot(menu, stack -> stack.is(Items.BONE_MEAL));
				if (sourceMenuSlot < 0) {
					// Counted bonemeal but no matching slot: treat as a bad read
					// rather than declaring the chest empty.
					unsyncedChests++;
					closeGui();
					wait = 1;
					step = Step.NEXT;
					return Status.RUNNING;
				}

				beforeBonemeal = safeBonemealCount();
				verifyTicks = 0;
				stableTicks = 0;
				click(menu, sourceMenuSlot, 0, ClickType.QUICK_MOVE);
				wait = guiDelay();
				step = Step.VERIFY;
			}
			case VERIFY -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				if (!isContainerOpen()) {
					return nextChest();
				}
				AbstractContainerMenu menu = player.containerMenu;
				// The click is only a prediction; a shift-click that the server
				// never processed still moves the stack locally and would leave
				// phantom bonemeal in the inventory.  Only the server-confirmed
				// count proves the transfer.
				int after = safeBonemealCount();
				if (after > beforeBonemeal) {
					// A rejected click is rolled back by the server's full-state
					// correction, so the increase must survive a few ticks before
					// the chest is closed and the stack is trusted.
					if (++stableTicks < 3) {
						wait = 1;
						return Status.RUNNING;
					}
					current.cachedBonemeal = countContainerBonemeal(menu);
					current.lastCheckedTick = controller.tickCount();
					SpruceFarmClient.LOGGER.info("Took {} bonemeal (inventory now {})",
						after - beforeBonemeal, after);
					closeGui();
					wait = 2;
					step = Step.CLOSE;
					return Status.RUNNING;
				}
				stableTicks = 0;

				if (verifyTicks++ < 15) {
					// A rejection is usually corrected within a tick or two; one
					// second is plenty before trying the click again.
					wait = 1;
					return Status.RUNNING;
				}
				if (verifyAttempts++ == 0) {
					// A rejected click is usually a state-id race; one more try
					// from the same slot is enough before changing strategy.
					verifyTicks = 0;
					stableTicks = 0;
					click(menu, sourceMenuSlot, 0, ClickType.QUICK_MOVE);
					wait = guiDelay();
					return Status.RUNNING;
				}
				if (!manualTried) {
					// Shift-click was ignored; try a direct pickup and place.
					SpruceFarmClient.LOGGER.warn("Shift-click was not confirmed after {} ticks (state={} carried={})",
						verifyTicks, menu.getStateId(), menu.getCarried());
					manualTried = true;
					step = Step.MANUAL_PICKUP;
					wait = guiDelay();
					return Status.RUNNING;
				}
				return fail("The server did not confirm the bonemeal transfer. Reopen the chest and press Start.");
			}
			case MANUAL_PICKUP -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				if (!isContainerOpen()) {
					return nextChest();
				}
				AbstractContainerMenu menu = player.containerMenu;
				manualTarget = findEmptyPlayerSlot(menu);
				if (manualTarget < 0) {
					closeGui();
					return fail("Inventory is full; cannot take bonemeal.");
				}
				click(menu, sourceMenuSlot, 0, ClickType.PICKUP);
				wait = guiDelay();
				step = Step.MANUAL_PLACE;
			}
			case MANUAL_PLACE -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				if (!isContainerOpen()) {
					return nextChest();
				}
				AbstractContainerMenu menu = player.containerMenu;
				if (menu.getCarried().isEmpty()) {
					// The pickup itself was rejected; nothing to place.
					return fail("Could not take bonemeal from the chest. Reopen it and press Start.");
				}
				click(menu, manualTarget, 0, ClickType.PICKUP);
				wait = guiDelay();
				if (!menu.getCarried().isEmpty()) {
					click(menu, sourceMenuSlot, 0, ClickType.PICKUP);
					closeGui();
					return fail("Could not take bonemeal from the chest.");
				}
				// Confirm the manual transfer against the server as well.
				verifyTicks = 0;
				verifyAttempts = 1;
				step = Step.VERIFY;
				wait = guiDelay();
			}
			case CLOSE -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				if (isContainerOpen()) {
					closeGui();
					wait = 3;
					return Status.RUNNING;
				}
				controller.setChestHint(current == null ? Long.MIN_VALUE : current.key);
				step = Step.DONE;
				return Status.DONE;
			}
			case NEXT -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				if (current != null) {
					tried.add(current.key);
				}
				return nextChest();
			}
			case DONE -> {
				return Status.DONE;
			}
			default -> {
				return fail(error.isEmpty() ? "Could not restock bonemeal" : error);
			}
		}

		return Status.RUNNING;
	}

	private String pos(ChestGroup group) {
		if (group == null || group.primary == null) {
			return "?";
		}
		return group.primary.getX() + " " + group.primary.getY() + " " + group.primary.getZ();
	}

	private Status nextChest() {
		closeGui();
		tried.add(current == null ? Long.MIN_VALUE : current.key);
		for (ChestGroup group : groups) {
			if (!tried.contains(group.key)) {
				current = group;
				step = Step.OPEN;
				wait = 2;
				timeout = openTimeout();
				openAttempts = 0;
				emptyReads = 0;
				pendingChestHit = null;
				return Status.RUNNING;
			}
		}
		SpruceFarmClient.LOGGER.warn("Restock gave up: opened={} unsynced={} groups={} error={}",
			openedChests, unsyncedChests, groups.size(), error);
		if (openedChests == 0) {
			return fail("Could not open any chest (" + groups.size()
				+ " detected). Stand directly on the chests with a clear view; they may also be inside a claim you cannot use.");
		}
		if (unsyncedChests > 0) {
			return fail("Opened " + openedChests + " chest(s), but the server never sent their contents ("
				+ unsyncedChests + " unsynchronised). Move slightly and press Start again.");
		}
		return fail("Opened " + openedChests + " chest(s) and read them: they contain no bonemeal. Refill them and press Start.");
	}

	private BlockHitResult findChestHit() {
		if (current == null) {
			return null;
		}
		BlockHitResult otherChest = null;
		for (BlockPos pos : current.blocks) {
			BlockHitResult hit = InteractionHelper.raycastBlock(client, pos);
			if (hit == null && config.interactThroughSigns) {
				// The player stands inside a sign box built on the chests; the
				// thin boards cover the chest faces but cannot stop a click.
				hit = InteractionHelper.signBlockedHit(client, pos);
			}
			if (hit != null) {
				return hit;
			}
			// The four chests form one 2x2 block.  From the centre the ray to
			// one chest can hit its neighbour first; a player simply opens
			// whichever chest the crosshair is actually on.
			BlockHitResult blocker = InteractionHelper.blockingHit(client, pos);
			if (blocker != null && current.blocks.contains(blocker.getBlockPos())) {
				otherChest = blocker;
			}
			if (config.directFallback && InteractionHelper.canInteract(client, pos)) {
				return InteractionHelper.directHitNear(client, pos, net.minecraft.core.Direction.UP);
			}
		}
		return otherChest;
	}

	/**
	 * Bonemeal total from the mirrored inventory, which is updated by our own
	 * clicks and corrected by any packet the server sends.  That makes an
	 * out-of-band change such as an admin {@code /clear} visible here while a
	 * plain client read would keep the stale stack forever.
	 */
	private int safeBonemealCount() {
		if (ServerState.inventoryKnown()) {
			return ServerState.count(Items.BONE_MEAL);
		}
		return InventoryHelper.countBonemeal(player);
	}

	private int countContainerBonemeal(AbstractContainerMenu menu) {
		int total = 0;
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (slot.container != player.getInventory() && slot.getItem().is(Items.BONE_MEAL)) {
				total += slot.getItem().getCount();
			}
		}
		return total;
	}

	private int findContainerSlot(AbstractContainerMenu menu, Predicate<ItemStack> predicate) {
		int best = -1;
		int bestCount = -1;
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (slot.container != player.getInventory() && predicate.test(slot.getItem())) {
				int count = slot.getItem().getCount();
				if (count > bestCount) {
					best = i;
					bestCount = count;
				}
			}
		}
		return best;
	}

	private int findEmptyPlayerSlot(AbstractContainerMenu menu) {
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (slot.container == player.getInventory() && slot.getItem().isEmpty()) {
				return i;
			}
		}
		return -1;
	}

	private void click(AbstractContainerMenu menu, int slot, int button, ClickType type) {
		if (client.gameMode == null || slot < 0) {
			return;
		}
		client.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, player);
		// The click is applied to the client menu but the server never echoes
		// an accepted click, so keep the mirror in step with it.
		ServerState.syncFromClient(player);
	}

	private boolean isContainerOpen() {
		return client.screen != null
			&& player.containerMenu != null
			&& player.containerMenu != player.inventoryMenu
			&& player.containerMenu.containerId != 0;
	}

	private void closeGui() {
		if (player.containerMenu != null && player.containerMenu != player.inventoryMenu) {
			player.closeContainer();
		}
		if (client.screen != null) {
			client.setScreen(null);
		}
	}

	private int guiDelay() {
		return config.fastMode ? 2 : config.guiDelayTicks;
	}

	private int openDelay() {
		return config.fastMode ? 1 : Math.max(4, config.guiDelayTicks);
	}

	private int contentDelay() {
		return config.fastMode ? 2 : Math.max(4, config.guiDelayTicks);
	}

	private int openTimeout() {
		// Give the open-screen packet a full two seconds on a busy server;
		// a too-short timeout was indistinguishable from a rejected click.
		return config.fastMode ? 40 : config.guiOpenTimeoutTicks;
	}

	private Status fail(String message) {
		this.error = message;
		closeGui();
		step = Step.FAILED;
		return Status.FAILED;
	}

	@Override
	public String status() {
		return switch (step) {
			case WAIT_OPEN -> "Opening bonemeal chest";
			case TAKE -> "Reading chest contents";
			case VERIFY, MANUAL_PICKUP, MANUAL_PLACE -> "Taking one stack of bonemeal";
			case CLOSE -> "Closing chest";
			case NEXT -> "Checking the other chest";
			default -> "Restocking bonemeal";
		};
	}

	@Override
	public String error() {
		return error;
	}

	@Override
	public void cancel() {
		closeGui();
	}
}
