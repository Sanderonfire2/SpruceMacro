package com.sprucefarm;

import com.sprucefarm.logic.SellPlanner;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

public final class InventoryHelper {
	public static final int HOTBAR_SIZE = 9;
	public static final int INVENTORY_SIZE = 36;

	private InventoryHelper() {
	}

	public static int count(Player player, Predicate<ItemStack> predicate) {
		Inventory inventory = player.getInventory();
		int total = 0;
		for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && predicate.test(stack)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	public static int countSaplings(Player player) {
		return count(player, stack -> stack.is(Items.SPRUCE_SAPLING));
	}

	public static int countBonemeal(Player player) {
		return count(player, stack -> stack.is(Items.BONE_MEAL));
	}

	public static int countLogs(Player player) {
		return count(player, stack -> stack.is(ItemTags.SPRUCE_LOGS));
	}

	public static int countSticks(Player player) {
		return count(player, stack -> stack.is(Items.STICK));
	}

	public static int freeSlots(Player player) {
		Inventory inventory = player.getInventory();
		int free = 0;
		for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
			if (inventory.getItem(slot).isEmpty()) {
				free++;
			}
		}
		return free;
	}

	public static boolean hasRoomFor(Player player, ItemStack wanted) {
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				return true;
			}
			if (stack.is(wanted.getItem()) && stack.getCount() < stack.getMaxStackSize()) {
				return true;
			}
		}
		return false;
	}

	public static int findSlot(Player player, Predicate<ItemStack> predicate) {
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && predicate.test(stack)) {
				return slot;
			}
		}
		return -1;
	}

	public static int findAxe(Player player, FarmConfig config) {
		return findSlot(player, stack -> isAxe(stack, config));
	}

	public static boolean isAxe(ItemStack stack, FarmConfig config) {
		if (stack.isEmpty()) {
			return false;
		}
		if (config.axeOverride != null) {
			// An explicit override means exactly that item is the tree axe;
			// a vanilla axe lying earlier in the inventory must not win.
			return stack.getItem() == config.axeOverride;
		}
		if (stack.is(ItemTags.AXES)) {
			return true;
		}
		Identifier id = BuiltInRegistries.ITEM.getKey(stack.getItem());
		if (id != null && id.getPath().toLowerCase(Locale.ROOT).contains("axe")) {
			return true;
		}
		return stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains("axe");
	}

	public static boolean isSellable(ItemStack stack) {
		return !stack.isEmpty()
			&& (stack.is(ItemTags.SPRUCE_LOGS) || stack.is(Items.STICK) || stack.is(Items.SPRUCE_SAPLING));
	}

	public static boolean isSapling(ItemStack stack) {
		return stack.is(Items.SPRUCE_SAPLING);
	}

	public static List<SellPlanner.Stack> sellStacks(Player player) {
		List<SellPlanner.Stack> stacks = new ArrayList<>();
		Inventory inventory = player.getInventory();
		for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty()) {
				continue;
			}
			boolean sapling = stack.is(Items.SPRUCE_SAPLING);
			if (isSellable(stack)) {
				stacks.add(new SellPlanner.Stack(slot, stack.getCount(), sapling, true));
			}
		}
		return stacks;
	}

	public static int sellableCount(Player player, int keepSaplings) {
		int total = 0;
		for (SellPlanner.Sale sale : SellPlanner.plan(sellStacks(player), keepSaplings)) {
			total += sale.count();
		}
		return total;
	}

	/** Menu slot index for an inventory index in the player's inventory menu. */
	public static int menuSlotForInventoryIndex(int inventoryIndex) {
		if (inventoryIndex < 0 || inventoryIndex >= INVENTORY_SIZE) {
			return -1;
		}
		if (inventoryIndex < HOTBAR_SIZE) {
			return InventoryMenu.USE_ROW_SLOT_START + inventoryIndex;
		}
		return inventoryIndex;
	}

	/** Inverse of {@link #menuSlotForInventoryIndex(int)}. */
	public static int inventoryIndexForMenuSlot(int menuSlot) {
		if (menuSlot >= InventoryMenu.USE_ROW_SLOT_START && menuSlot < InventoryMenu.USE_ROW_SLOT_START + HOTBAR_SIZE) {
			return menuSlot - InventoryMenu.USE_ROW_SLOT_START;
		}
		if (menuSlot >= HOTBAR_SIZE && menuSlot < INVENTORY_SIZE) {
			return menuSlot;
		}
		return -1;
	}

	public static int[] itemCounts(Player player) {
		return new int[]{
			countLogs(player),
			countSticks(player),
			countSaplings(player)
		};
	}

	public static boolean isPlainStack(ItemStack stack) {
		return !stack.isEmpty() && !stack.is(Items.BONE_MEAL) && !stack.is(Items.SPRUCE_SAPLING);
	}

	public static Item itemOf(ItemStack stack) {
		return stack.getItem();
	}
}
