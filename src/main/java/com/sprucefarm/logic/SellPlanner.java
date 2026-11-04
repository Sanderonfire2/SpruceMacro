package com.sprucefarm.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out exactly which inventory stacks should be moved into the sell GUI.
 *
 * <p>Only whole stacks are ever moved.  A partial sale needs one click per
 * item, which is slower than everything else in a farming cycle put together;
 * keeping every stack intact also makes the transfer predictable.  Spruce
 * saplings are sold stack by stack, and a stack is only taken when at least
 * {@code keepSaplings} would remain afterwards, so the reserve can never be
 * consumed by a split.</p>
 */
public final class SellPlanner {
	private SellPlanner() {
	}

	public record Stack(int slot, int count, boolean sapling, boolean sellable) {
	}

	public record Sale(int slot, int count) {
	}

	public static int excessSaplings(int totalSaplings, int keepSaplings) {
		return Math.max(0, totalSaplings - keepSaplings);
	}

	/**
	 * True when the free inventory slots have fallen to the configured
	 * threshold and there is something worth selling.  Farming continues
	 * normally while this is false.
	 */
	public static boolean needsSale(int freeSlots, int threshold, int sellableCount) {
		return sellableCount > 0 && freeSlots <= threshold;
	}

	public static List<Sale> plan(List<Stack> stacks, int keepSaplings) {
		List<Sale> result = new ArrayList<>();
		int totalSaplings = 0;
		for (Stack stack : stacks) {
			if (stack.sapling() && stack.sellable() && stack.count() > 0) {
				totalSaplings += stack.count();
			}
		}

		// Whole sapling stacks, as long as the reserve survives.
		for (Stack stack : stacks) {
			if (!stack.sapling() || !stack.sellable() || stack.count() <= 0) {
				continue;
			}
			if (totalSaplings - stack.count() < keepSaplings) {
				continue;
			}
			result.add(new Sale(stack.slot(), stack.count()));
			totalSaplings -= stack.count();
		}

		// Logs and sticks always go in full.
		for (Stack stack : stacks) {
			if (stack.sellable() && !stack.sapling() && stack.count() > 0) {
				result.add(new Sale(stack.slot(), stack.count()));
			}
		}

		return result;
	}
}
