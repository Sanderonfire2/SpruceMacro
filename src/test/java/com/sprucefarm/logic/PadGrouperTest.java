package com.sprucefarm.logic;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PadGrouperTest {
	private static PadGrouper.GridPos p(int x, int y, int z) {
		return new PadGrouper.GridPos(x, y, z);
	}

	@Test
	void groupsA2x2PadTogether() {
		List<PadGrouper.GridPos> spots = List.of(
			p(0, 0, 0), p(1, 0, 0), p(0, 0, 1), p(1, 0, 1)
		);
		assertEquals(1, PadGrouper.countPads(spots));
	}

	@Test
	void separatesPadsWithAGap() {
		List<PadGrouper.GridPos> spots = List.of(
			p(0, 0, 0), p(1, 0, 0), p(0, 0, 1), p(1, 0, 1),
			p(3, 0, 0), p(4, 0, 0), p(3, 0, 1), p(4, 0, 1)
		);
		List<List<PadGrouper.GridPos>> groups = PadGrouper.group(spots);
		assertEquals(2, groups.size());
		assertEquals(4, groups.get(0).size());
		assertEquals(4, groups.get(1).size());
	}

	@Test
	void doesNotGroupDifferentHeights() {
		List<PadGrouper.GridPos> spots = List.of(
			p(0, 0, 0), p(1, 0, 0),
			p(0, 1, 0), p(1, 1, 0)
		);
		assertEquals(2, PadGrouper.countPads(spots));
	}

	@Test
	void groupsARow() {
		List<PadGrouper.GridPos> spots = List.of(
			p(0, 0, 0), p(1, 0, 0), p(2, 0, 0), p(3, 0, 0)
		);
		assertEquals(1, PadGrouper.countPads(spots));
	}

	@Test
	void emptyInputHasNoPads() {
		assertEquals(0, PadGrouper.countPads(List.of()));
	}
}
