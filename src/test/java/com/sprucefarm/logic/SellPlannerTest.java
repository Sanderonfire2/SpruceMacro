package com.sprucefarm.logic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SellPlannerTest {
	private static SellPlanner.Stack saplings(int slot, int count) {
		return new SellPlanner.Stack(slot, count, true, true);
	}

	private static SellPlanner.Stack logs(int slot, int count) {
		return new SellPlanner.Stack(slot, count, false, true);
	}

	@Test
	void keepsTheWholeReserve() {
		List<SellPlanner.Sale> plan = SellPlanner.plan(
			List.of(saplings(0, 64), saplings(1, 12)), 64);
		// The loose 12 can go because exactly 64 remain; the full stack stays.
		assertEquals(List.of(new SellPlanner.Sale(1, 12)), plan);
	}

	@Test
	void sellsOneFullStackAndLeavesTheReserve() {
		List<SellPlanner.Sale> plan = SellPlanner.plan(
			List.of(saplings(0, 64), saplings(1, 64)), 64);
		assertEquals(List.of(new SellPlanner.Sale(0, 64)), plan);
	}

	@Test
	void partialStacksAreNeverSplit() {
		List<SellPlanner.Sale> plan = SellPlanner.plan(
			List.of(saplings(0, 40), saplings(1, 30), saplings(2, 30)), 64);
		// Removing the 40 would leave 60, so the greedy pass takes a 30 and
		// stops; every sale is still a complete slot.
		assertEquals(List.of(new SellPlanner.Sale(1, 30)), plan);
	}

	@Test
	void logsAndSticksAlwaysGoInFull() {
		List<SellPlanner.Sale> plan = SellPlanner.plan(
			List.of(saplings(0, 70), logs(1, 64), new SellPlanner.Stack(2, 32, false, true)), 64);
		assertTrue(plan.contains(new SellPlanner.Sale(1, 64)));
		assertTrue(plan.contains(new SellPlanner.Sale(2, 32)));
		// 70 - 1 stack would leave fewer than 64, so the saplings stay.
		assertEquals(2, plan.size());
	}

	@Test
	void capacityTriggersSellingOnlyWhenNeeded() {
		assertTrue(SellPlanner.needsSale(6, 6, 30));
		assertTrue(SellPlanner.needsSale(2, 6, 1));
		assertFalse(SellPlanner.needsSale(7, 6, 30));
		assertFalse(SellPlanner.needsSale(0, 6, 0));
	}
}
