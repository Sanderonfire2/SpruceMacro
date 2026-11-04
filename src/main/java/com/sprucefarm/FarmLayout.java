package com.sprucefarm;

import com.sprucefarm.logic.PadGrouper;
import com.sprucefarm.logic.PadGrouper.GridPos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class FarmLayout {
	private FarmLayout() {
	}

	public record ScanResult(List<PlantSpot> spots, int pads, int blocked, int unreachable) {
	}

	public static ScanResult scan(Minecraft client, FarmConfig config) {
		List<PlantSpot> spots = new ArrayList<>();
		if (client.player == null || client.level == null) {
			return new ScanResult(spots, 0, 0, 0);
		}

		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		BlockPos base = player.blockPosition();
		BlockPos feet = base;
		BlockPos head = base.above();
		int unreachable = 0;

		Set<BlockPos> grass = new LinkedHashSet<>();
		for (int dx = -config.scanHorizontal; dx <= config.scanHorizontal; dx++) {
			for (int dz = -config.scanHorizontal; dz <= config.scanHorizontal; dz++) {
				for (int dy = -config.scanDown; dy <= config.scanUp; dy++) {
					BlockPos pos = base.offset(dx, dy, dz);
					// Spruce growth can turn the grass block under the trunk into
					// dirt, so every dirt-like soil block is a valid pad block.
					if (!level.getBlockState(pos).is(BlockTags.DIRT)) {
						continue;
					}
					BlockPos above = pos.above();
					if (above.equals(feet) || above.equals(head)) {
						continue;
					}
					// Use the same check as the server, not the camera ray.  The
					// outer pads of a standard 8-pad farm sit just outside the
					// client's pick distance but inside the server's block
					// interaction range, so a camera-only test drops them.
					if (!InteractionHelper.canInteract(client, pos)) {
						unreachable++;
						continue;
					}
					grass.add(pos.immutable());
				}
			}
		}

		List<PadGrouper.GridPos> grid = new ArrayList<>();
		Map<PadGrouper.GridPos, PlantSpot> byPos = new LinkedHashMap<>();
		for (BlockPos pos : grass) {
			GridPos gridPos = new GridPos(pos.getX(), pos.getY(), pos.getZ());
			grid.add(gridPos);
			PlantSpot spot = new PlantSpot(pos, 0);
			BlockState above = level.getBlockState(spot.saplingPos);
			if (above.isAir()) {
				spot.state = SpotState.EMPTY;
			} else if (above.is(Blocks.SPRUCE_SAPLING)) {
				spot.state = SpotState.PLANTED;
			} else if (above.is(Blocks.SPRUCE_LOG)) {
				spot.state = SpotState.GROWN;
			} else {
				spot.state = SpotState.BLOCKED;
				spot.note = above.getBlock().getName().getString();
			}
			byPos.put(gridPos, spot);
		}

		int pads = 0;
		int blocked = 0;
		for (List<PadGrouper.GridPos> group : PadGrouper.group(grid)) {
			boolean usable = false;
			for (PadGrouper.GridPos pos : group) {
				PlantSpot spot = byPos.get(pos);
				if (spot != null && spot.state != SpotState.BLOCKED) {
					usable = true;
					break;
				}
			}

			// A group that is entirely obstructed is support structure, not a pad.
			// Very large grass areas are not part of this isolated-pad layout either.
			if (!usable || group.size() > 64) {
				blocked += group.size();
				continue;
			}

			int padId = pads++;
			for (PadGrouper.GridPos pos : group) {
				PlantSpot spot = byPos.get(pos);
				if (spot == null) {
					continue;
				}
				spot.padId = padId;
				if (spot.state == SpotState.BLOCKED) {
					blocked++;
				}
				spots.add(spot);
			}
		}

		// Plant/grow/harvest the farthest spots first.  A nearer sapling or
		// trunk then sits behind the camera ray instead of covering the next
		// pad, which keeps strict visible-only interactions working.  The scan
		// already filters out spots beyond the real interaction range, so the
		// first far spot is still a legal placement.
		Vec3 sortEye = player.getEyePosition();
		spots.sort(Comparator
			.comparingDouble((PlantSpot spot) -> -sortEye.distanceTo(Vec3.atCenterOf(spot.saplingPos)))
			.thenComparingInt(spot -> spot.padId)
			.thenComparingInt(spot -> spot.grassPos.getY())
			.thenComparingInt(spot -> spot.grassPos.getX())
			.thenComparingInt(spot -> spot.grassPos.getZ()));

		return new ScanResult(spots, pads, blocked, unreachable);
	}

	public static List<ChestGroup> findChests(Minecraft client, FarmConfig config) {
		List<ChestGroup> result = new ArrayList<>();
		if (client.player == null || client.level == null) {
			return result;
		}

		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		BlockPos base = player.blockPosition();
		Vec3 eye = player.getEyePosition();
		Map<Long, ChestGroup> groups = new LinkedHashMap<>();

		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				for (int dy = -4; dy <= 1; dy++) {
					BlockPos pos = base.offset(dx, dy, dz);
					BlockState state = level.getBlockState(pos);
					if (!(state.getBlock() instanceof ChestBlock)) {
						continue;
					}
					if (!(level.getBlockEntity(pos) instanceof ChestBlockEntity)) {
						continue;
					}

					long key;
					ChestType type = state.getValue(ChestBlock.TYPE);
					if (type == ChestType.SINGLE) {
						key = pos.asLong();
					} else {
						BlockPos other = pos.relative(ChestBlock.getConnectedDirection(state));
						key = Math.min(pos.asLong(), other.asLong());
					}

					ChestGroup group = groups.get(key);
					if (group == null) {
						double distance = eye.distanceTo(InteractionHelper.aimTop(pos));
						group = new ChestGroup(key, pos, distance);
						groups.put(key, group);
					}
					group.add(pos);
				}
			}
		}

		for (ChestGroup group : groups.values()) {
			BlockPos best = null;
			double bestDistance = Double.MAX_VALUE;
			for (BlockPos pos : group.blocks) {
				double distance = eye.distanceTo(InteractionHelper.aimTop(pos));
				if (InteractionHelper.canInteract(client, pos) && distance < bestDistance) {
					best = pos;
					bestDistance = distance;
				}
			}
			if (best == null) {
				continue;
			}
			group.primary = best;
			group.distance = bestDistance;
			result.add(group);
		}

		result.sort(Comparator.comparingDouble(group -> group.distance));
		return result;
	}

	public static boolean isSpruceLog(BlockState state) {
		return state.is(Blocks.SPRUCE_LOG);
	}
}
