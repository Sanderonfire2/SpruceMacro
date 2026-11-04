package com.sprucefarm.test;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

public final class TestSupport implements ModInitializer {
	/** Simulates the farm's water stream in the test world. */
	public static volatile boolean vacuumItems;

	/** Simulates a strict anti-cheat: a sapling placement whose line of sight
	 * crosses a sign is rolled back and the click is reported as failed. */
	public static volatile boolean strictSigns;

	/**
	 * Emulates a claim/ghost-support refusal on one otherwise visible spot:
	 * the placement succeeds, then the server removes it and reports FAIL.
	 */
	public static volatile BlockPos rejectSpot;

	/**
	 * Emulates the server-side anti-cheat setting for a private server: a sign
	 * board is non-solid, so the line-of-sight check treats it as transparent
	 * and a click on the soil behind it is allowed.
	 */
	public static volatile boolean signAwareAntiCheat;

	/** Emulates the Shard Axe: remove connected logs above a broken base log. */
	public static volatile boolean limitedAxe;
	/** Maximum logs the emulated axe removes from one fell. */
	public static volatile int axeLogLimit = 8;
	/** Ticks before the first removed log; simulates a server-side fell. */
	public static volatile int axeFallDelayTicks;
	/** Ticks between removals; 0 removes the whole segment at once. */
	public static volatile int axeFallIntervalTicks;

	private static final List<FellJob> FELLS = new ArrayList<>();

	private static final class FellJob {
		private final ServerLevel level;
		private final List<BlockPos> logs;
		private final int interval;
		private int cursor;
		private int wait;

		private FellJob(ServerLevel level, List<BlockPos> logs, int delay, int interval) {
			this.level = level;
			this.logs = logs;
			this.wait = delay;
			this.interval = interval;
		}
	}

	@Override
	public void onInitialize() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
			Commands.literal("sell").executes(context -> {
				ServerPlayer player = context.getSource().getPlayerOrException();
				SimpleContainer container = new SimpleContainer(54);
				player.openMenu(new SimpleMenuProvider(
					(syncId, inventory, p) -> ChestMenu.sixRows(syncId, inventory, container),
					Component.literal("Sell")
				));
				return 1;
			})
		));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			if (vacuumItems) {
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					for (ItemEntity item : player.level().getEntitiesOfClass(
						ItemEntity.class, player.getBoundingBox().inflate(64.0D))) {
						item.teleportTo(player.getX(), player.getY(), player.getZ());
					}
				}
			}
			if (FELLS.isEmpty()) {
				return;
			}
			Iterator<FellJob> iterator = FELLS.iterator();
			while (iterator.hasNext()) {
				FellJob job = iterator.next();
				if (job.level.getServer() != server) {
					continue;
				}
				if (job.cursor >= job.logs.size()) {
					iterator.remove();
					continue;
				}
				if (job.wait > 0) {
					job.wait--;
					continue;
				}
				do {
					job.level.removeBlock(job.logs.get(job.cursor), false);
					job.cursor++;
				} while (job.interval == 0 && job.cursor < job.logs.size());
				job.wait = job.interval;
				if (job.cursor >= job.logs.size()) {
					iterator.remove();
				}
			}
		});
	}

	/**
	 * Schedules the emulated axe fell for the connected spruce logs above
	 * {@code base}.  The removal can be delayed and spread over ticks, which
	 * is exactly the server behaviour the harvest verification must tolerate.
	 */
	public static void startFell(ServerLevel level, BlockPos base) {
		if (!limitedAxe) {
			return;
		}
		List<BlockPos> logs = new ArrayList<>();
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(base.above());
		while (!queue.isEmpty() && logs.size() < axeLogLimit) {
			BlockPos pos = queue.poll();
			if (!seen.add(pos) || pos.getY() < base.getY()) {
				continue;
			}
			if (!level.getBlockState(pos).is(Blocks.SPRUCE_LOG)) {
				continue;
			}
			logs.add(pos.immutable());
			for (Direction direction : Direction.values()) {
				queue.add(pos.relative(direction));
			}
		}
		FELLS.add(new FellJob(level, logs, axeFallDelayTicks, axeFallIntervalTicks));
	}
}
