package com.sprucefarm;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class ChestGroup {
	public final long key;
	public final List<BlockPos> blocks = new ArrayList<>();
	public BlockPos primary;
	public double distance;
	public int cachedBonemeal = -1;
	public long lastCheckedTick = -1L;
	public boolean empty;

	public ChestGroup(long key, BlockPos primary, double distance) {
		this.key = key;
		this.primary = primary;
		this.distance = distance;
	}

	public void add(BlockPos pos) {
		if (!blocks.contains(pos)) {
			blocks.add(pos);
		}
	}

	public String stockLabel(long now) {
		if (cachedBonemeal < 0) {
			return "unknown";
		}
		if (lastCheckedTick < 0) {
			return Integer.toString(cachedBonemeal);
		}
		long seconds = Math.max(0L, (now - lastCheckedTick) / 20L);
		return cachedBonemeal + " (" + seconds + "s ago)";
	}
}
