package com.sprucefarm;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The last state the server explicitly told us about.  The normal client
 * world and inventory are optimistic: a predicted block or a predicted
 * inventory click is visible locally before the server accepts it.  A strict
 * server can reject either one and send the real state back, which is what
 * looks like a sapling that "despawns instantly".
 *
 * <p>This tracker records only {@code ClientboundBlockUpdatePacket},
 * {@code ClientboundSectionBlocksUpdatePacket},
 * {@code ClientboundContainerSetContentPacket},
 * {@code ClientboundContainerSetSlotPacket} and
 * {@code ClientboundSetHeldSlotPacket}; no client prediction is stored here.
 * A missing entry means "not told yet", not "air".</p>
 */
public final class ServerState {
	private static final Map<BlockPos, BlockEntry> BLOCKS = new HashMap<>();
	private static final Map<Integer, ItemStack> SLOTS = new HashMap<>();
	private static final Map<Integer, Long> CONTAINER_CONTENT = new HashMap<>();
	/** containerId -> (menu slot -> player inventory index, -1 for chest slots). */
	private static final Map<Integer, SlotLayout> LAYOUTS = new HashMap<>();
	private static final int MAX_LAYOUTS = 8;
	private static long sequence;
	private static long slotSequence;
	private static boolean inventoryKnown;
	private static int heldSlot = -1;

	private ServerState() {
	}

	private record BlockEntry(BlockState state, long sequence) {
	}

	private record SlotLayout(int menuSize, int[] inventoryIndex) {
	}

	public static synchronized void recordBlock(BlockPos pos, BlockState state) {
		BLOCKS.put(pos.immutable(), new BlockEntry(state, ++sequence));
	}

	public static synchronized BlockState block(BlockPos pos) {
		BlockEntry entry = BLOCKS.get(pos);
		return entry == null ? null : entry.state;
	}

	/** Sequence of the last server update for this position, or -1 if none. */
	public static synchronized long blockSequence(BlockPos pos) {
		BlockEntry entry = BLOCKS.get(pos);
		return entry == null ? -1L : entry.sequence;
	}

	public static synchronized long sequence() {
		return sequence;
	}

	/** Counts real, server-sent content packets per container id.  A chest
	 * screen can be visible before its contents arrive; this lets the restock
	 * task tell "empty chest" apart from "contents not synced yet". */
	public static synchronized void recordContainerContent(int containerId) {
		if (containerId != 0) {
			CONTAINER_CONTENT.merge(containerId, 1L, Long::sum);
		}
	}

	/** Copy of the current per-container counts, used as a snapshot before a
	 * click so a later packet is proof that this chest's contents arrived. */
	public static synchronized Map<Integer, Long> containerContentSnapshot() {
		return new HashMap<>(CONTAINER_CONTENT);
	}

	public static synchronized long containerContentCount(int containerId) {
		return CONTAINER_CONTENT.getOrDefault(containerId, 0L);
	}

	/**
	 * Remembers which menu slots of an open container belong to the player
	 * inventory.  The client can close a chest before the server has, and the
	 * server then keeps broadcasting that chest's contents; without this map
	 * those packets are dropped and the local inventory keeps a phantom stack
	 * the server already removed.
	 */
	public static synchronized void rememberMenuLayout(int containerId, net.minecraft.world.inventory.AbstractContainerMenu menu,
		net.minecraft.world.entity.player.Player player) {
		if (containerId == 0 || menu.slots.size() <= 36) {
			return;
		}
		int[] mapping = new int[menu.slots.size()];
		Arrays.fill(mapping, -1);
		int playerSlots = 0;
		for (int slotIndex = 0; slotIndex < menu.slots.size(); slotIndex++) {
			net.minecraft.world.inventory.Slot slot = menu.slots.get(slotIndex);
			if (slot.container == player.getInventory()) {
				mapping[slotIndex] = slot.getContainerSlot();
				playerSlots++;
			}
		}
		if (playerSlots == 0) {
			return;
		}
		if (LAYOUTS.size() >= MAX_LAYOUTS && !LAYOUTS.containsKey(containerId)) {
			LAYOUTS.clear();
		}
		LAYOUTS.put(containerId, new SlotLayout(menu.slots.size(), mapping));
	}

	/**
	 * Applies a content packet for a container that is no longer open, using
	 * the layout that was recorded while it was.  A packet whose item count
	 * does not match the remembered menu (a reused container id, for example)
	 * is ignored rather than misread.
	 */
	public static synchronized boolean applyLateContent(int containerId, List<ItemStack> items) {
		SlotLayout layout = LAYOUTS.get(containerId);
		if (layout == null || layout.menuSize() != items.size()) {
			return false;
		}
		boolean applied = false;
		for (int slotIndex = 0; slotIndex < layout.inventoryIndex().length; slotIndex++) {
			int inventoryIndex = layout.inventoryIndex()[slotIndex];
			if (inventoryIndex < 0) {
				continue;
			}
			int menuSlot = InventoryHelper.menuSlotForInventoryIndex(inventoryIndex);
			if (menuSlot < 0) {
				continue;
			}
			SLOTS.put(menuSlot, items.get(slotIndex).copy());
			applied = true;
		}
		if (applied) {
			inventoryKnown = true;
			slotSequence++;
		}
		return applied;
	}

	public static synchronized void recordContents(int containerId, java.util.List<ItemStack> items) {
		if (containerId != 0) {
			return;
		}
		SLOTS.clear();
		for (int slot = 0; slot < items.size(); slot++) {
			SLOTS.put(slot, items.get(slot).copy());
		}
		inventoryKnown = true;
		slotSequence++;
	}

	/**
	 * Copies the player's inventory as the client sees it.  The server does not
	 * echo the slot changes of a container click it accepted, so a mirror that
	 * only listens to packets would lag behind every click the mod makes.  A
	 * rejected click is corrected by the server's full-state packet, which
	 * arrives later and overwrites this snapshot.
	 */
	public static synchronized void syncFromClient(net.minecraft.world.entity.player.Player player) {
		for (int index = 0; index < InventoryHelper.INVENTORY_SIZE; index++) {
			int menuSlot = InventoryHelper.menuSlotForInventoryIndex(index);
			if (menuSlot >= 0) {
				SLOTS.put(menuSlot, player.getInventory().getItem(index).copy());
			}
		}
		inventoryKnown = true;
		slotSequence++;
	}

	/** Record a player inventory stack, using inventory indices 0-8/9-35. */
	public static synchronized void recordInventorySlot(int inventoryIndex, ItemStack stack) {
		int menuSlot = InventoryHelper.menuSlotForInventoryIndex(inventoryIndex);
		if (menuSlot < 0) {
			return;
		}
		SLOTS.put(menuSlot, stack.copy());
		inventoryKnown = true;
		slotSequence++;
	}

	public static synchronized void recordSlot(int containerId, int slot, ItemStack stack, int stateId) {
		if (containerId != 0) {
			return;
		}
		SLOTS.put(slot, stack.copy());
		inventoryKnown = true;
		slotSequence++;
	}

	public static synchronized void recordHeldSlot(int slot) {
		if (slot >= 0 && slot < InventoryHelper.HOTBAR_SIZE) {
			heldSlot = slot;
		}
	}

	public static synchronized int heldSlot() {
		return heldSlot;
	}

	public static synchronized boolean inventoryKnown() {
		return inventoryKnown;
	}

	public static synchronized long slotSequence() {
		return slotSequence;
	}

	/** Server-confirmed item in an inventory-menu slot, or null if unknown. */
	public static synchronized ItemStack slot(int menuSlot) {
		return SLOTS.get(menuSlot);
	}

	/** Total count of an item across the server-confirmed player inventory. */
	public static synchronized int count(net.minecraft.world.item.Item item) {
		int total = 0;
		for (ItemStack stack : SLOTS.values()) {
			if (stack != null && stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	public static synchronized void clear() {
		BLOCKS.clear();
		SLOTS.clear();
		inventoryKnown = false;
		heldSlot = -1;
		sequence = 0;
		slotSequence = 0;
		CONTAINER_CONTENT.clear();
		LAYOUTS.clear();
	}
}
