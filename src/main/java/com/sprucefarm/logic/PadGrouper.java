package com.sprucefarm.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Groups planting spots into connected pads. Two spots belong to the same pad
 * when they are horizontally adjacent and at the same height.
 */
public final class PadGrouper {
	private PadGrouper() {
	}

	public record GridPos(int x, int y, int z) {
	}

	public static int countPads(Collection<GridPos> spots) {
		return group(spots).size();
	}

	public static List<List<GridPos>> group(Collection<GridPos> spots) {
		Set<GridPos> remaining = new HashSet<>(spots);
		List<List<GridPos>> groups = new ArrayList<>();

		while (!remaining.isEmpty()) {
			GridPos seed = remaining.iterator().next();
			List<GridPos> group = new ArrayList<>();
			Deque<GridPos> queue = new ArrayDeque<>();
			queue.add(seed);
			remaining.remove(seed);

			while (!queue.isEmpty()) {
				GridPos current = queue.removeFirst();
				group.add(current);
				for (GridPos neighbour : neighbours(current)) {
					if (remaining.remove(neighbour)) {
						queue.addLast(neighbour);
					}
				}
			}

			groups.add(group);
		}

		return groups;
	}

	public static Map<GridPos, Integer> groupIds(Collection<GridPos> spots) {
		List<List<GridPos>> groups = group(spots);
		Map<GridPos, Integer> ids = new HashMap<>();
		for (int i = 0; i < groups.size(); i++) {
			for (GridPos pos : groups.get(i)) {
				ids.put(pos, i);
			}
		}
		return ids;
	}

	private static List<GridPos> neighbours(GridPos pos) {
		List<GridPos> result = new ArrayList<>(4);
		result.add(new GridPos(pos.x() + 1, pos.y(), pos.z()));
		result.add(new GridPos(pos.x() - 1, pos.y(), pos.z()));
		result.add(new GridPos(pos.x(), pos.y(), pos.z() + 1));
		result.add(new GridPos(pos.x(), pos.y(), pos.z() - 1));
		return result;
	}
}
