package com.sprucefarm;

import com.sprucefarm.logic.SellPlanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Opens the server's /sell GUI, moves whole sellable stacks into it, closes it,
 * and verifies that the items actually left the inventory.
 *
 * <p>The transfer is batched: several independent whole-stack quick-moves are
 * sent per tick instead of waiting for one stack at a time.  Each click is
 * checked against the client's own menu prediction on the next pause, and only
 * a click the menu ignored falls back to the slower pickup/place path.  No
 * sapling stack is ever split, so the 64-sapling reserve cannot be eaten by a
 * partial transfer.</p>
 */
public final class SellTask implements FarmTask {
	private enum Step {
		SEND,
		WAIT_OPEN,
		PLAN,
		TRANSFER,
		MANUAL_PICKUP,
		MANUAL_PLACE,
		MANUAL_VERIFY,
		CLOSE,
		VERIFY,
		DONE,
		FAILED
	}

	private record PendingSale(int menuSlot, int before) {
	}

	private final FarmController controller;
	private final Minecraft client;
	private final FarmConfig config;
	private final LocalPlayer player;
	/** Wall-clock start, logged with the result so a slow sell is visible. */
	private final long startedAt = System.nanoTime();

	private Step step = Step.SEND;
	private int wait;
	private int timeout;
	private int taskTicks;
	private String error = "";

	private int beforeSellable;
	private int beforeSaplings;
	private List<SellPlanner.Sale> plan = new ArrayList<>();
	private int planIndex;
	private boolean planEmpty;
	private int rounds;
	private final List<PendingSale> batch = new ArrayList<>();
	private int verifyTicks;

	private int sourceMenuSlot = -1;
	private int manualCount;
	private int verifyRetries;

	public SellTask(FarmController controller) {
		this.controller = controller;
		this.client = controller.client();
		this.config = controller.config();
		this.player = client.player;
	}

	@Override
	public Status tick() {
		if (player == null || client.level == null) {
			return fail("No player");
		}
		taskTicks++;
		if (taskTicks > 1600) {
			return fail("Sell task timed out");
		}

		switch (step) {
			case SEND -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				player.connection.sendCommand("sell");
				wait = config.fastMode ? 1 : 2;
				timeout = config.guiOpenTimeoutTicks;
				step = Step.WAIT_OPEN;
			}
			case WAIT_OPEN -> {
				if (isContainerOpen()) {
					step = Step.PLAN;
					// Let the server finish opening the menu before the first
					// click; DonutSMP rejects clicks that arrive before its
					// menu state is ready.  Fast mode still keeps a two-tick
					// round trip for that handshake.
					wait = config.fastMode ? 2 : Math.max(2, Math.min(config.guiDelayTicks, 3));
					return Status.RUNNING;
				}
				if (--timeout <= 0) {
					return fail("The /sell GUI did not open. Check that /sell is available.");
				}
			}
			case PLAN -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				beforeSellable = InventoryHelper.sellableCount(player, config.keepSaplings);
				beforeSaplings = InventoryHelper.countSaplings(player);
				plan = SellPlanner.plan(InventoryHelper.sellStacks(player), config.keepSaplings);
				planIndex = 0;
				rounds = 0;
				batch.clear();
				if (plan.isEmpty()) {
					planEmpty = true;
					step = Step.CLOSE;
					return Status.RUNNING;
				}
				step = Step.TRANSFER;
			}
			case TRANSFER -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				if (verifyTicks > 0) {
					verifyTicks--;
					return Status.RUNNING;
				}
				AbstractContainerMenu menu = player.containerMenu;
				if (!isContainerOpen()) {
					return fail("The sell GUI closed unexpectedly.");
				}

				// Check the previous batch once.  A shift-click the local menu
				// ignored is retried through the pickup/place path.
				if (!batch.isEmpty()) {
					PendingSale failed = null;
					for (PendingSale sale : batch) {
						if (menu.getSlot(sale.menuSlot()).getItem().getCount() >= sale.before()) {
							failed = sale;
							break;
						}
					}
					batch.clear();
					if (failed != null) {
						Slot slot = menu.getSlot(failed.menuSlot());
						if (slot.getItem().isEmpty()) {
							// It left anyway, just after the check.
							return Status.RUNNING;
						}
						sourceMenuSlot = failed.menuSlot();
						manualCount = slot.getItem().getCount();
						step = Step.MANUAL_PICKUP;
						wait = 1;
						return Status.RUNNING;
					}
				}

				if (planIndex >= plan.size()) {
					// Another pass picks up stacks that a server-side partial
					// move left behind, or that became sellable meanwhile.
					rounds++;
					if (rounds < 3) {
						List<SellPlanner.Sale> more =
							SellPlanner.plan(InventoryHelper.sellStacks(player), config.keepSaplings);
						if (!more.isEmpty()) {
							plan = more;
							planIndex = 0;
							return Status.RUNNING;
						}
					}
					step = Step.CLOSE;
					return Status.RUNNING;
				}

				int clicked = 0;
				while (planIndex < plan.size() && clicked < config.sellClicksPerTick) {
					SellPlanner.Sale sale = plan.get(planIndex++);
					int menuSlot = menuSlotForInventory(menu, sale.slot());
					if (menuSlot < 0) {
						continue;
					}
					Slot slot = menu.getSlot(menuSlot);
					if (slot.getItem().isEmpty()) {
						continue;
					}
					int before = slot.getItem().getCount();
					click(menu, menuSlot, 0, ClickType.QUICK_MOVE);
					batch.add(new PendingSale(menuSlot, before));
					clicked++;
				}
				if (clicked == 0) {
					// Everything left in the plan is already empty or belongs
					// to a slot the menu will not let us move.
					step = Step.CLOSE;
					return Status.RUNNING;
				}
				wait = config.sellClickDelayTicks;
				verifyTicks = config.sellVerifyTicks;
			}
			case MANUAL_PICKUP -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				AbstractContainerMenu menu = player.containerMenu;
				if (!isContainerOpen() || sourceMenuSlot < 0) {
					return fail("The sell GUI closed unexpectedly.");
				}
				ItemStack stack = menu.getSlot(sourceMenuSlot).getItem();
				if (stack.isEmpty()) {
					step = Step.TRANSFER;
					return Status.RUNNING;
				}
				manualCount = stack.getCount();
				click(menu, sourceMenuSlot, 0, ClickType.PICKUP);
				wait = config.guiDelayTicks;
				step = Step.MANUAL_PLACE;
			}
			case MANUAL_PLACE -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				AbstractContainerMenu menu = player.containerMenu;
				if (!isContainerOpen()) {
					return fail("The sell GUI closed unexpectedly.");
				}
				if (menu.getCarried().isEmpty()) {
					step = Step.TRANSFER;
					return Status.RUNNING;
				}
				int target = findEmptyContainerSlot(menu, menu.getCarried());
				if (target < 0) {
					click(menu, sourceMenuSlot, 0, ClickType.PICKUP);
					return fail("The sell GUI is full; items were returned.");
				}
				click(menu, target, 0, ClickType.PICKUP);
				wait = config.guiDelayTicks;
				step = Step.MANUAL_VERIFY;
			}
			case MANUAL_VERIFY -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				AbstractContainerMenu menu = player.containerMenu;
				if (!isContainerOpen()) {
					return fail("The sell GUI closed unexpectedly.");
				}
				if (!menu.getCarried().isEmpty()) {
					click(menu, sourceMenuSlot, 0, ClickType.PICKUP);
					return fail("Could not place items into the sell GUI.");
				}
				step = Step.TRANSFER;
			}
			case CLOSE -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				closeGui();
				wait = config.fastMode ? 2 : 4;
				step = Step.VERIFY;
			}
			case VERIFY -> {
				if (wait > 0) {
					wait--;
					return Status.RUNNING;
				}
				if (isContainerOpen()) {
					closeGui();
					return fail("The sell GUI did not close.");
				}
				int afterSaplings = InventoryHelper.countSaplings(player);
				if (beforeSaplings >= config.keepSaplings && afterSaplings < config.keepSaplings) {
					return fail("The sale removed part of the " + config.keepSaplings + "-sapling reserve.");
				}
				int after = InventoryHelper.sellableCount(player, config.keepSaplings);
				if (planEmpty || after < beforeSellable) {
					SpruceFarmClient.LOGGER.info("Sold {} sellable item(s) in {} pass(es), {} ms; {} still sellable",
						beforeSellable - after, Math.max(1, rounds),
						(System.nanoTime() - startedAt) / 1_000_000L, after);
					step = Step.DONE;
					return Status.DONE;
				}
				// Server plugins can sell a tick or two after the close packet
				// arrives, so give the inventory sync a short grace period.
				if (verifyRetries++ < 5) {
					wait = config.fastMode ? 3 : 5;
					return Status.RUNNING;
				}
				return fail("The sell GUI did not remove the items. Nothing was sold.");
			}
			case DONE -> {
				return Status.DONE;
			}
			default -> {
				return error.isEmpty() ? Status.FAILED : fail(error);
			}
		}

		return Status.RUNNING;
	}

	private void click(AbstractContainerMenu menu, int slot, int button, ClickType type) {
		if (client.gameMode == null) {
			return;
		}
		client.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, player);
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

	private int menuSlotForInventory(AbstractContainerMenu menu, int inventoryIndex) {
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (slot.container == player.getInventory() && slot.getContainerSlot() == inventoryIndex) {
				return i;
			}
		}
		return -1;
	}

	private int findEmptyContainerSlot(AbstractContainerMenu menu, ItemStack stack) {
		for (int i = 0; i < menu.slots.size(); i++) {
			Slot slot = menu.getSlot(i);
			if (slot.container != player.getInventory() && slot.getItem().isEmpty() && slot.mayPlace(stack)) {
				return i;
			}
		}
		return -1;
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
			case WAIT_OPEN -> "Waiting for /sell GUI";
			case TRANSFER, MANUAL_PICKUP, MANUAL_PLACE, MANUAL_VERIFY ->
				"Selling whole stacks (" + Math.min(planIndex, plan.size()) + "/" + plan.size() + ")";
			case CLOSE, VERIFY -> "Closing sell GUI";
			default -> "Selling";
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
