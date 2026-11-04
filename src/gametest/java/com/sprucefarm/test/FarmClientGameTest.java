package com.sprucefarm.test;

import com.sprucefarm.FarmConfig;
import com.sprucefarm.FarmController;
import com.sprucefarm.FarmScreen;
import com.sprucefarm.InventoryHelper;
import com.sprucefarm.PlantSpot;
import com.sprucefarm.ServerState;
import com.sprucefarm.SpruceFarmClient;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Locale;

/**
 * Rebuilds the 8-pad / 32-spot farm from the supplied litematic and drives the
 * real controller through it.  The layout check runs in creative; the full
 * plant/grow/harvest/sell cycle runs in survival because creative breaks do
 * not drop items.  The special axe is emulated by removing the trunk above the
 * base log, exactly as the server plugin does.
 */
public final class FarmClientGameTest implements FabricClientGameTest {
	private static final int FLOOR_Y = 99;
	private static final int CHEST_Y = 100;
	private static final int PAD_Y = 101;
	private static final int BASE_Y = 102;
	private static final double CENTER = 9.0D;

	@Override
	@SuppressWarnings("UnstableApiUsage")
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
			// The client window is not focused while the test runs headless-ish;
			// vanilla would pause the client and stall every wait inside the
			// controller, turning a 30-second cycle into minutes.
			context.runOnClient(mc -> mc.options.pauseOnLostFocus = false);
			singleplayer.getClientWorld().waitForChunksRender();
			TestServerContext server = singleplayer.getServer();
			configureWorld(server);
			buildSchematicFarm(server);
			fillChests(server);

			server.runCommand("tp @a " + CENTER + " 101 " + CENTER);
			server.runCommand("gamemode creative @a");
			giveKit(server, true);
			context.waitTicks(40);
			singleplayer.getClientWorld().waitForChunksRender();
			context.waitTicks(20);
			context.waitFor(mc -> mc.player != null && mc.player.onGround(), 120);

			context.runOnClient(mc -> {
				FarmConfig config = SpruceFarmClient.config();
				config.actionDelayTicks = 4;
				// The litematic's 32 pods form eight conceptual 2x2 pads, but
				// two pairs touch along an edge, so connected-component
				// grouping reports six physical pads.  The 32 spots are the
				// functional layout; the user's real farm has eight separated
				// pads and keeps expectedPads = 8.
				config.expectedPads = 6;
				config.expectedSpots = 32;
				config.requireFullLayout = true;
				config.directFallback = false;
				SpruceFarmClient.controller().rescan();
			});
			context.waitTicks(5);
			context.runOnClient(mc -> System.out.println("[sprucefarm-test] scan player=" + mc.player.blockPosition()
				+ " spots=" + SpruceFarmClient.controller().spots().size()
				+ " pads=" + SpruceFarmClient.controller().pads()
				+ " blocked=" + SpruceFarmClient.controller().blockedSpots()
				+ " unreachable=" + SpruceFarmClient.controller().unreachableSpots()
				+ " pod=" + mc.level.getBlockState(new BlockPos(8, PAD_Y, 4))
				+ " above=" + mc.level.getBlockState(new BlockPos(8, PAD_Y + 1, 4))
				+ " error=" + SpruceFarmClient.controller().error()));
			context.waitFor(mc -> {
				FarmController live = SpruceFarmClient.controller();
				failOnError(live);
				return live.pads() == 6 && live.spots().size() == 32
					&& live.blockedSpots() == 0 && live.unreachableSpots() == 0;
			}, 240);
			System.out.println("[sprucefarm-test] CREATIVE schematic layout: "
				+ context.computeOnClient(mc -> SpruceFarmClient.controller().pads()) + " pads / "
				+ context.computeOnClient(mc -> SpruceFarmClient.controller().spots().size()) + " spots");

			context.setScreen(() -> new FarmScreen(SpruceFarmClient.controller()));
			context.waitTick();
			context.takeScreenshot("sprucefarm-schematic");
			context.setScreen(() -> null);

			// Creative cycle: prove the exact schematic works with the axe and
			// strict, visible-only interactions.  Creative drops nothing, so
			// the sell/restock verification happens in survival below.
			context.runOnClient(mc -> SpruceFarmClient.controller().start());
			waitPlanted(context, server, 32, 2000);
			waitGrown(context, server, 32, 6000);
			emulateAxe(server);
			// The next cycle must start as soon as the harvest is verified.
			// The removed collect stage used to hold the controller in a
			// separate stage for ten more ticks before replanting, so a direct
			// PLANT/SELL/RESTOCK transition within six ticks of the last
			// harvest proves the gap is gone.
			int[] harvestTick = {0};
			context.waitFor(mc -> {
				FarmController live = SpruceFarmClient.controller();
				failOnError(live);
				if (harvestTick[0] == 0 && live.totalStats().harvested >= 32) {
					harvestTick[0] = live.tickCount();
				}
				return harvestTick[0] != 0
					&& (live.stage() == FarmController.Stage.PLANT
						|| live.stage() == FarmController.Stage.SELL
						|| live.stage() == FarmController.Stage.RESTOCK);
			}, 600);
			int restartGap = context.computeOnClient(
				mc -> SpruceFarmClient.controller().tickCount()) - harvestTick[0];
			if (restartGap > 6) {
				throw new AssertionError("The next cycle was still idle " + restartGap
					+ " ticks after the last harvest");
			}
			System.out.println("[sprucefarm-test] PASS next cycle starts directly ("
				+ restartGap + " ticks)");
			context.runOnClient(mc -> SpruceFarmClient.controller().stop());
			System.out.println("[sprucefarm-test] PASS creative schematic 32/32/32");

			// Clear anything the creative cycle planted before the survival
			// run.  Only the planting layer is touched; pods, water and walls
			// stay exactly as placed from the litematic.
			server.runCommand("kill @e[type=item]");
			server.runCommand("fill 4 102 4 13 140 13 minecraft:air");
			server.runCommand("gamemode survival @a");
			server.runCommand("tp @a " + CENTER + " 101 " + CENTER);
			giveKit(server, false);
			fillChests(server);
			context.waitTicks(20);
			context.waitFor(mc -> mc.player != null && mc.player.onGround(), 120);

			TestSupport.vacuumItems = true;
			context.runOnClient(mc -> {
				SpruceFarmClient.config().directFallback = false;
				SpruceFarmClient.config().sellFreeSlots = 6;
				SpruceFarmClient.controller().start();
			});
			waitPlanted(context, server, 32, 2000);
			waitGrown(context, server, 32, 6000);
			emulateAxe(server);
			waitHarvested(context, 32, 3000);
			// The inventory is nowhere near full after one cycle, so the mod
			// must carry on farming and keep the logs instead of selling after
			// every cycle.
			context.waitFor(mc -> {
				FarmController live = SpruceFarmClient.controller();
				failOnError(live);
				return live.totalStats().harvested >= 32
					&& live.stage() != FarmController.Stage.SELL
					&& InventoryHelper.countLogs(mc.player) > 0
					&& InventoryHelper.countSaplings(mc.player) >= 64;
			}, 3600);
			context.runOnClient(mc -> SpruceFarmClient.controller().stop());
			context.waitTicks(20);
			System.out.println("[sprucefarm-test] PASS no sale while the inventory has room");

			// Now fill the inventory so only a few slots are free.  A fresh
			// Start must sell once, in whole stacks, keep 64 saplings, and then
			// resume farming.
			for (int i = 0; i < 26; i++) {
				server.runCommand("give @a minecraft:spruce_log 64");
			}
			context.waitTicks(10);
			context.runOnClient(mc -> SpruceFarmClient.controller().start());
			context.waitFor(mc -> {
				FarmController live = SpruceFarmClient.controller();
				failOnError(live);
				return InventoryHelper.countLogs(mc.player) == 0
					&& InventoryHelper.countSaplings(mc.player) >= 64
					&& InventoryHelper.countSaplings(mc.player) < 128
					&& InventoryHelper.freeSlots(mc.player) > 6
					&& live.stage() != FarmController.Stage.SELL;
			}, 3000);
			context.runOnClient(mc -> SpruceFarmClient.controller().stop());
			TestSupport.vacuumItems = false;
			System.out.println("[sprucefarm-test] PASS capacity sale keeps 64 saplings");

			// Toggle test: a glass wall blocks the west pad.  Strict mode must
			// not send through it; enabling directFallback must.  Refresh the
			// kit first so this phase tests line-of-sight, not leftover
			// inventory state from the sale/restock cycle.
			server.runCommand("fill 4 102 4 13 140 13 minecraft:air");
			server.runCommand("tp @a " + CENTER + " 101 " + CENTER);
			giveKit(server, false);
			fillChests(server);
			context.waitTicks(20);
			server.runCommand("setblock 7 102 8 minecraft:glass");
			server.runCommand("setblock 7 102 9 minecraft:glass");
			context.runOnClient(mc -> {
				SpruceFarmClient.config().directFallback = true;
				SpruceFarmClient.controller().rescan();
				SpruceFarmClient.controller().start();
			});
			waitPlanted(context, server, 32, 2200);
			context.runOnClient(mc -> SpruceFarmClient.controller().stop());
			// Let the last in-flight click and its server correction land before
			// the next phase scans; otherwise a late rollback was processed in
			// the new cycle and stalled it.
			context.waitTicks(20);
			server.runCommand("setblock 7 102 8 minecraft:air");
			server.runCommand("setblock 7 102 9 minecraft:air");
			server.runCommand("fill 4 102 4 13 102 13 minecraft:air");
			context.runOnClient(mc -> SpruceFarmClient.config().directFallback = false);
			System.out.println("[sprucefarm-test] PASS directFallback toggle");

			// A tree left standing at Start must be harvested, not rejected.
			server.runCommand("setblock 8 " + BASE_Y + " 4 minecraft:spruce_log");
			context.waitTicks(5);
			// Let the client see the placed trunk before Start scans: starting
			// on the old chunk snapshot made the controller plant first and then
			// report the tree as an error, which was a flaky harness failure.
			context.waitFor(mc -> mc.level.getBlockState(new BlockPos(8, BASE_Y, 4))
				.is(net.minecraft.world.level.block.Blocks.SPRUCE_LOG), 100);
			context.runOnClient(mc -> SpruceFarmClient.controller().start());
			long[] lastSettle = {0L};
			context.waitFor(mc -> {
				FarmController live = SpruceFarmClient.controller();
				long now = System.currentTimeMillis();
				if (now - lastSettle[0] > 2000L) {
					lastSettle[0] = now;
					System.out.println("[sprucefarm-test] standing settle: stage=" + live.stage()
						+ " status=" + live.status() + " error=" + live.error());
				}
				return live.stage() == FarmController.Stage.HARVEST
					|| live.stage() == FarmController.Stage.ERROR;
			}, 600);
			context.waitFor(mc -> SpruceFarmClient.controller().totalStats().harvested >= 1, 700);
			context.runOnClient(mc -> SpruceFarmClient.controller().stop());
			System.out.println("[sprucefarm-test] PASS standing-tree resume");

			// Empty chests and player bonemeal: the controller must report the
			// empty-chest error, then recover after one chest is refilled.
			// Put a sapling on every spot so the controller enters GROW
			// immediately. This phase is about the restock path, and growing
			// a dense 32-tree ring would only test line-of-sight.
			server.runCommand("fill 4 102 4 13 140 13 minecraft:air");
			giveKit(server, false);
			plantSaplings(server);
			emptyChests(server);
			server.runCommand("clear @a minecraft:bone_meal");
			// The clear command only changes slots the server actually has, so
			// wait until both the client and the server-confirmed snapshot
			// agree that the player holds no bonemeal before starting.
			long[] lastClear = {0L};
			context.waitFor(mc -> {
				long now = System.currentTimeMillis();
				int serverBonemeal = ServerState.count(net.minecraft.world.item.Items.BONE_MEAL);
				if (now - lastClear[0] > 1000L) {
					lastClear[0] = now;
					System.out.println("[sprucefarm-test] clear wait: client="
						+ InventoryHelper.countBonemeal(mc.player) + " server=" + serverBonemeal
						+ " known=" + ServerState.inventoryKnown());
				}
				return mc.player != null && serverBonemeal == 0;
			}, 400);
			context.waitTicks(10);
			context.runOnClient(mc -> SpruceFarmClient.controller().start());
			long[] lastStatus = {0L};
			context.waitFor(mc -> {
				FarmController live = SpruceFarmClient.controller();
				long now = System.currentTimeMillis();
				if (now - lastStatus[0] > 1000L) {
					lastStatus[0] = now;
					System.out.println("[sprucefarm-test] empty-chest wait: stage=" + live.stage()
						+ " status=" + live.status() + " error=" + live.error()
						+ " bonemeal=" + (mc.player == null ? -1 : InventoryHelper.countBonemeal(mc.player))
						+ " serverBonemeal=" + ServerState.count(net.minecraft.world.item.Items.BONE_MEAL)
						+ " known=" + ServerState.inventoryKnown());
				}
				return live.stage() == FarmController.Stage.ERROR
					&& live.error().toLowerCase(Locale.ROOT).contains("bonemeal");
			}, 2200);

			server.runCommand("item replace block 8 " + CHEST_Y + " 8 container.0 with minecraft:bone_meal 64");
			context.waitTicks(10);
			context.runOnClient(mc -> SpruceFarmClient.controller().start());
			long[] lastRefillStatus = {0L};
			context.waitFor(mc -> {
				FarmController live = SpruceFarmClient.controller();
				long now = System.currentTimeMillis();
				if (now - lastRefillStatus[0] > 1000L) {
					lastRefillStatus[0] = now;
					System.out.println("[sprucefarm-test] refill wait: stage=" + live.stage()
						+ " status=" + live.status() + " error=" + live.error()
						+ " bonemeal=" + (mc.player == null ? -1 : InventoryHelper.countBonemeal(mc.player))
						+ " serverBonemeal=" + ServerState.count(net.minecraft.world.item.Items.BONE_MEAL)
						+ " known=" + ServerState.inventoryKnown());
				}
				return (live.stage() == FarmController.Stage.PLANT || live.stage() == FarmController.Stage.GROW)
					&& ServerState.count(net.minecraft.world.item.Items.BONE_MEAL) > 0;
			}, 2200);
			context.runOnClient(mc -> SpruceFarmClient.controller().stop());
			System.out.println("[sprucefarm-test] PASS chest refill recovery");

			// A server-side fell is gradual: the base log vanishes at once and
			// the plugin removes the rest over the next seconds.  The harvest
			// verification must wait for that instead of reporting leftover logs.
			server.runCommand("fill 4 102 4 13 140 13 minecraft:air");
			server.runCommand("tp @a " + CENTER + " 101 " + CENTER);
			giveKit(server, false);
			fillChests(server);
			context.waitTicks(10);
			server.runCommand("fill 10 102 10 10 109 10 minecraft:spruce_log");
			TestSupport.limitedAxe = true;
			TestSupport.axeLogLimit = 8;
			TestSupport.axeFallDelayTicks = 40;
			TestSupport.axeFallIntervalTicks = 5;
			context.waitFor(mc -> mc.level.getBlockState(new BlockPos(10, 102, 10)).is(Blocks.SPRUCE_LOG), 100);
			Vec3 fellStart = context.computeOnClient(mc -> mc.player.position());
			context.runOnClient(mc -> SpruceFarmClient.controller().start());
			// The new cycle must start while the server is still felling, not
			// wait for the last log.  Stop as soon as the harvest hands over to
			// PLANT/RESTOCK/GROW so the test axe's scheduled fell can finish on
			// its own; otherwise the bot would grow the new farm over the test
			// column and make the top-log check meaningless.
			context.waitFor(mc -> {
				FarmController live = SpruceFarmClient.controller();
				failOnError(live);
				return live.totalStats().harvested >= 1
					&& (live.stage() == FarmController.Stage.PLANT
						|| live.stage() == FarmController.Stage.RESTOCK
						|| live.stage() == FarmController.Stage.GROW);
			}, 400);
			context.runOnClient(mc -> SpruceFarmClient.controller().stop());
			System.out.println("[sprucefarm-test] delayed fell: next cycle started while the fell ran");
			context.waitFor(mc -> mc.level.getBlockState(new BlockPos(10, 109, 10)).isAir(), 400);
			double fellMoved = context.computeOnClient(mc -> mc.player.position().distanceTo(fellStart));
			if (fellMoved > 0.05D) {
				throw new AssertionError("Player moved during the delayed fell: " + fellMoved);
			}
			int fellExtras = context.computeOnClient(mc -> SpruceFarmClient.controller().totalStats().extraFells);
			if (fellExtras != 0) {
				throw new AssertionError("A delayed server fell was treated as a leftover: extras=" + fellExtras);
			}
			TestSupport.limitedAxe = false;
			context.waitTicks(20);
			System.out.println("[sprucefarm-test] PASS delayed server fell is awaited");

			// A fell limit leaves logs standing.  The controller must chop the
			// new lowest log with the same axe from the same position and finish
			// the tree without walking or jumping.
			server.runCommand("fill 4 102 4 13 140 13 minecraft:air");
			server.runCommand("tp @a " + CENTER + " 101 " + CENTER);
			giveKit(server, false);
			fillChests(server);
			context.waitTicks(10);
			server.runCommand("fill 10 102 10 10 106 10 minecraft:spruce_log");
			TestSupport.limitedAxe = true;
			TestSupport.axeLogLimit = 2;
			TestSupport.axeFallDelayTicks = 0;
			TestSupport.axeFallIntervalTicks = 0;
			context.waitFor(mc -> mc.level.getBlockState(new BlockPos(10, 102, 10)).is(Blocks.SPRUCE_LOG), 100);
			Vec3 leftoverStart = context.computeOnClient(mc -> mc.player.position());
			context.runOnClient(mc -> SpruceFarmClient.controller().start());
			long[] lastLeftover = {0L};
			context.waitFor(mc -> {
				FarmController live = SpruceFarmClient.controller();
				failOnError(live);
				long now = System.currentTimeMillis();
				if (now - lastLeftover[0] > 2000L) {
					lastLeftover[0] = now;
					System.out.println("[sprucefarm-test] leftover fell: stage=" + live.stage()
						+ " status=" + live.status() + " extras=" + live.totalStats().extraFells);
				}
				return live.totalStats().harvested >= 1
					&& live.totalStats().extraFells >= 1
					&& mc.level.getBlockState(new BlockPos(10, 106, 10)).isAir();
			}, 1600);
			double leftoverMoved = context.computeOnClient(mc -> mc.player.position().distanceTo(leftoverStart));
			if (leftoverMoved > 0.05D) {
				throw new AssertionError("Player moved while finishing leftover logs: " + leftoverMoved);
			}
			context.runOnClient(mc -> SpruceFarmClient.controller().stop());
			TestSupport.limitedAxe = false;
			context.waitTicks(20);
			System.out.println("[sprucefarm-test] PASS axe limit finished from the same spot");

			// When the axe cannot finish even the retries, the floating trunk
			// must be skipped and the farm must keep running instead of
			// stopping with a "logs remaining" error.
			server.runCommand("fill 4 102 4 13 140 13 minecraft:air");
			server.runCommand("tp @a " + CENTER + " 101 " + CENTER);
			giveKit(server, false);
			fillChests(server);
			context.waitTicks(10);
			server.runCommand("fill 10 102 10 10 106 10 minecraft:spruce_log");
			TestSupport.limitedAxe = true;
			TestSupport.axeLogLimit = 0;
			TestSupport.axeFallDelayTicks = 0;
			TestSupport.axeFallIntervalTicks = 0;
			Vec3 skipStart = context.computeOnClient(mc -> mc.player.position());
			context.runOnClient(mc -> {
				FarmConfig cfg = SpruceFarmClient.config();
				cfg.maxHarvestPasses = 1;
				cfg.fellQuietTicks = 20;
				cfg.harvestSettleTicks = 10;
				SpruceFarmClient.controller().start();
			});
			context.waitFor(mc -> {
				FarmController live = SpruceFarmClient.controller();
				if (live.stage() == FarmController.Stage.ERROR) {
					throw new AssertionError("Floating tree stopped the farm: " + live.error());
				}
				return live.totalStats().skippedTrees >= 1;
			}, 1600);
			double skipMoved = context.computeOnClient(mc -> mc.player.position().distanceTo(skipStart));
			if (skipMoved > 0.05D) {
				throw new AssertionError("Player moved while skipping a floating tree: " + skipMoved);
			}
			boolean trunkKept = context.computeOnClient(
				mc -> mc.level.getBlockState(new BlockPos(10, 106, 10)).is(Blocks.SPRUCE_LOG));
			if (!trunkKept) {
				throw new AssertionError("Skipped tree was expected to stay in place");
			}
			context.runOnClient(mc -> {
				SpruceFarmClient.controller().stop();
				FarmConfig cfg = SpruceFarmClient.config();
				cfg.maxHarvestPasses = 10;
				cfg.fellQuietTicks = 60;
				cfg.harvestSettleTicks = 20;
			});
			TestSupport.limitedAxe = false;
			server.runCommand("fill 4 102 4 13 140 13 minecraft:air");
			context.waitTicks(20);
			System.out.println("[sprucefarm-test] PASS floating tree skipped, farm continues");

			int pads = context.computeOnClient(mc -> SpruceFarmClient.controller().pads());
			int spots = context.computeOnClient(mc -> SpruceFarmClient.controller().spots().size());
			System.out.println("[sprucefarm-test] PASS schematic=" + pads + "/" + spots
				+ " strict=on directFallback=off-by-default");

			// Through-sapling planting: the exact geometry that used to stop
			// with "Cannot see the planting spot ... blocked by Spruce
			// Sapling".  The pad's own diagonal sapling covers the top face,
			// but a sapling has no collision shape, so the soil behind it must
			// still be plantable and the server has to accept the click.
			server.runCommand("fill 4 102 4 13 140 13 minecraft:air");
			server.runCommand("setblock 8 102 5 minecraft:spruce_sapling");
			// Stand exactly on the pad's diagonal extension: the ray then
			// grazes the soil block under the covering sapling, which is the
			// geometry that used to defeat the single corner shot.
			server.runCommand("tp @a 10.5 102 7.5");
			server.runCommand("give @a minecraft:spruce_sapling 64");
			context.waitTicks(5);
			boolean[] throughHit = {false};
			context.runOnClient(mc -> {
				BlockPos target = new BlockPos(7, 101, 4);
				net.minecraft.world.phys.BlockHitResult hit =
					com.sprucefarm.InteractionHelper.plantHitThroughPlants(mc, target, true);
				throughHit[0] = hit != null;
				System.out.println("[sprucefarm-test] through-sapling hit=" + throughHit[0]);
				if (hit != null) {
					com.sprucefarm.InteractionHelper.ensureItem(mc,
						stack -> stack.is(net.minecraft.world.item.Items.SPRUCE_SAPLING),
						SpruceFarmClient.config());
					com.sprucefarm.InteractionHelper.useOn(mc, hit);
				}
			});
			if (!throughHit[0]) {
				throw new AssertionError("No through-sapling hit for the covered soil spot");
			}
			context.waitTicks(5);
			context.waitFor(mc -> mc.level.getBlockState(new BlockPos(7, 102, 4))
				.is(net.minecraft.world.level.block.Blocks.SPRUCE_SAPLING), 100);
			System.out.println("[sprucefarm-test] PASS soil click through a sapling");

			// Leaf handling is intentionally absent.  A leaf sitting on a spot
			// must be reported instead of broken, and the player must still not
			// move.  This is the accepted behaviour after the removal.
			server.runCommand("fill 4 102 4 13 140 13 minecraft:air");
			server.runCommand("tp @a " + CENTER + " 101 " + CENTER);
			giveKit(server, false);
			fillChests(server);
			server.runCommand("setblock 8 102 4 minecraft:spruce_leaves");
			context.waitTicks(10);
			Vec3 leafStart = context.computeOnClient(mc -> mc.player.position());
			context.runOnClient(mc -> SpruceFarmClient.controller().start());
			context.waitFor(mc -> {
				FarmController live = SpruceFarmClient.controller();
				return live.stage() == FarmController.Stage.ERROR
					&& live.error().contains("Spruce Leaves");
			}, 600);
			double leafMoved = context.computeOnClient(mc -> mc.player.position().distanceTo(leafStart));
			if (leafMoved > 0.05D) {
				throw new AssertionError("Player moved after a leaf blocked a spot: " + leafMoved);
			}
			boolean leafKept = context.computeOnClient(
				mc -> mc.level.getBlockState(new BlockPos(8, 102, 4)).is(Blocks.SPRUCE_LEAVES));
			if (!leafKept) {
				throw new AssertionError("The mod broke the blocking leaf");
			}
			context.runOnClient(mc -> SpruceFarmClient.controller().stop());
			context.waitTicks(20);
			System.out.println("[sprucefarm-test] PASS blocked leaf reported, not broken");

			// A spot the scan sees clearly can still be refused by the server
			// (a claim, a neighbour's block or a ghost support block).  The
			// farm must skip that one spot with a warning and keep the rest
			// planting instead of stopping on the rejected-sapling error.
			server.runCommand("fill 4 102 4 13 140 13 minecraft:air");
			server.runCommand("tp @a " + CENTER + " 101 " + CENTER);
			giveKit(server, true);
			fillChests(server);
			TestSupport.rejectSpot = new BlockPos(7, 102, 4);
			context.waitTicks(10);
			context.runOnClient(mc -> {
				SpruceFarmClient.config().maxPlantAttempts = 2;
				SpruceFarmClient.controller().rescan();
				SpruceFarmClient.controller().start();
			});
			context.waitFor(mc -> {
				FarmController live = SpruceFarmClient.controller();
				return live.warning().contains("Skipped 1/32")
					&& live.spots().stream().filter(spot -> !spot.abandoned)
						.allMatch(spot -> spot.state == com.sprucefarm.SpotState.PLANTED);
			}, 1200);
			String rejectedWarning = context.computeOnClient(mc -> SpruceFarmClient.controller().warning());
			System.out.println("[sprucefarm-test] PASS visible server-rejected spot skipped: "
				+ rejectedWarning);
			context.runOnClient(mc -> {
				SpruceFarmClient.controller().stop();
				SpruceFarmClient.config().maxPlantAttempts = 12;
			});
			TestSupport.rejectSpot = null;
			context.waitTicks(20);

			// Sign box: the player stands inside four signs on the centre chests
			// and four more at eye level, the user's layout.  The upper layer is
			// a wall from the eye to half the pads; a strict server rolls those
			// covered placements back.  First run with a strict server: the farm
			// must detect the rollback once, skip the hidden half, keep farming
			// the visible half and show a warning instead of stopping.
			TestSupport.strictSigns = true;
			TestSupport.signAwareAntiCheat = true;
			server.runCommand("fill 4 102 4 13 140 13 minecraft:air");
			server.runCommand("tp @a " + CENTER + " 101 " + CENTER);
			server.runCommand("gamemode creative @a");
			server.runCommand("setblock 8 101 8 minecraft:oak_sign[rotation=4]");
			server.runCommand("setblock 9 101 8 minecraft:oak_sign[rotation=12]");
			server.runCommand("setblock 8 101 9 minecraft:oak_sign[rotation=4]");
			server.runCommand("setblock 9 101 9 minecraft:oak_sign[rotation=12]");
			server.runCommand("setblock 8 102 8 minecraft:oak_sign[rotation=4]");
			server.runCommand("setblock 9 102 8 minecraft:oak_sign[rotation=12]");
			server.runCommand("setblock 8 102 9 minecraft:oak_sign[rotation=0]");
			server.runCommand("setblock 9 102 9 minecraft:oak_sign[rotation=8]");
			giveKit(server, true);
			fillChests(server);
			context.waitTicks(20);
			context.waitFor(mc -> mc.player != null && mc.player.onGround(), 120);
			// The block above the centre is the player's head; the signs must
			// not be mistaken for a planting spot or a blocked pad.
			context.runOnClient(mc -> {
				SpruceFarmClient.config().directFallback = false;
				SpruceFarmClient.controller().rescan();
				SpruceFarmClient.controller().start();
			});
			context.waitTicks(5);
			int signSpots = context.computeOnClient(mc -> SpruceFarmClient.controller().spots().size());
			if (signSpots != 32) {
				throw new AssertionError("Sign box changed the layout scan: " + signSpots + "/32 spots");
			}
			int standingDirect = context.computeOnClient(mc -> (int) SpruceFarmClient.controller().spots().stream()
				.filter(spot -> com.sprucefarm.InteractionHelper.raycastTop(mc, spot.grassPos) != null).count());
			context.runOnClient(mc -> mc.player.setShiftKeyDown(true));
			context.waitTicks(8);
			int crouchedDirect = context.computeOnClient(mc -> (int) SpruceFarmClient.controller().spots().stream()
				.filter(spot -> com.sprucefarm.InteractionHelper.raycastTop(mc, spot.grassPos) != null).count());
			context.runOnClient(mc -> mc.player.setShiftKeyDown(false));
			context.waitTicks(8);
			System.out.println("[sprucefarm-test] sign direct top-face hits: standing=" + standingDirect
				+ "/32 crouched=" + crouchedDirect + "/32");
			// The server-side setting the private anti-cheat needs: a sign is a
			// non-solid board, so the line-of-sight check ignores it and the
			// click on the soil behind it is allowed.  The client still sends a
			// real face hit on the soil, so this is not a through-wall click.
			waitPlanted(context, server, 32, 2400);
			int signPlanted = context.computeOnClient(mc -> (int) SpruceFarmClient.controller().spots().stream()
				.filter(spot -> spot.state == com.sprucefarm.SpotState.PLANTED).count());
			int signSkipped = context.computeOnClient(mc -> SpruceFarmClient.controller().skippedHidden());
			if (signPlanted != 32 || signSkipped != 0) {
				throw new AssertionError("Sign-aware anti-cheat planted " + signPlanted
					+ "/32, skipped " + signSkipped + " (expected 32 and 0)");
			}
			System.out.println("[sprucefarm-test] PASS sign-aware anti-cheat planted 32/32 through the upper signs");
			context.runOnClient(mc -> SpruceFarmClient.controller().stop());
			context.waitTicks(20);
			TestSupport.strictSigns = false;
			TestSupport.signAwareAntiCheat = false;
			// The four signs on the chests are below the sapling plane and
			// block nothing.  With the upper layer gone every spot must have a
			// direct top-face line, so a normal server accepts all 32.
			server.runCommand("setblock 8 102 8 minecraft:air");
			server.runCommand("setblock 9 102 8 minecraft:air");
			server.runCommand("setblock 8 102 9 minecraft:air");
			server.runCommand("setblock 9 102 9 minecraft:air");
			context.runOnClient(mc -> {
				SpruceFarmClient.controller().rescan();
				SpruceFarmClient.controller().start();
			});
			context.waitTicks(8);
			int lowerOnlyDirect = context.computeOnClient(mc -> (int) SpruceFarmClient.controller().spots().stream()
				.filter(spot -> spot.state == com.sprucefarm.SpotState.EMPTY)
				.filter(spot -> com.sprucefarm.InteractionHelper.raycastTop(mc, spot.grassPos) != null).count());
			System.out.println("[sprucefarm-test] sign direct top-face hits with lower signs only="
				+ lowerOnlyDirect + " empty spots");
			// No bonemeal in the creative kit: the restock opens a chest through
			// the sign boards as well, so this one cycle covers the whole path.
			waitPlanted(context, server, 32, 2400);
			System.out.println("[sprucefarm-test] PASS planted through the sign box");
			waitGrown(context, server, 32, 6000);
			emulateAxe(server);
			waitHarvested(context, 32, 3000);
			System.out.println("[sprucefarm-test] PASS grew and harvested through the sign box");
			context.runOnClient(mc -> SpruceFarmClient.controller().stop());
			context.waitTicks(20);
			server.runCommand("setblock 8 101 8 minecraft:air");
			server.runCommand("setblock 9 101 8 minecraft:air");
			server.runCommand("setblock 8 101 9 minecraft:air");
			server.runCommand("setblock 9 101 9 minecraft:air");
			server.runCommand("setblock 8 102 9 minecraft:air");
			server.runCommand("setblock 10 102 9 minecraft:air");
			server.runCommand("setblock 9 102 8 minecraft:air");
			server.runCommand("setblock 9 102 10 minecraft:air");
			server.runCommand("fill 4 102 4 13 140 13 minecraft:air");
			System.out.println("[sprucefarm-test] PASS sign box interactions");

		}
	}

	private static void configureWorld(TestServerContext server) {
		server.runCommand("gamerule doDaylightCycle false");
		server.runCommand("gamerule doMobSpawning false");
		server.runCommand("gamerule doWeatherCycle false");
		server.runCommand("time set day");
		server.runCommand("difficulty peaceful");
		server.runCommand("weather clear");
	}

	private static void buildSchematicFarm(TestServerContext server) {
		// Decoded from tree macro fix.litematic: an 18x18 cobblestone room,
		// one water layer, eight 2x2 podzol pads and a central 2x2 chest.
		server.runCommand("fill 0 " + FLOOR_Y + " 0 17 " + FLOOR_Y + " 17 minecraft:cobblestone");
		server.runCommand("fill 0 " + CHEST_Y + " 0 17 106 17 minecraft:cobblestone");
		server.runCommand("fill 1 " + CHEST_Y + " 1 16 106 16 minecraft:air");
		server.runCommand("fill 1 " + CHEST_Y + " 1 16 " + CHEST_Y + " 16 minecraft:water");

		server.runCommand("setblock 8 " + CHEST_Y + " 8 minecraft:chest[facing=north,type=left]");
		server.runCommand("setblock 9 " + CHEST_Y + " 8 minecraft:chest[facing=north,type=right]");
		server.runCommand("setblock 8 " + CHEST_Y + " 9 minecraft:chest[facing=north,type=left]");
		server.runCommand("setblock 9 " + CHEST_Y + " 9 minecraft:chest[facing=north,type=right]");

		server.runCommand("fill 7 " + PAD_Y + " 4 8 " + PAD_Y + " 5 minecraft:podzol");
		server.runCommand("fill 10 " + PAD_Y + " 6 11 " + PAD_Y + " 7 minecraft:podzol");
		server.runCommand("fill 12 " + PAD_Y + " 8 13 " + PAD_Y + " 9 minecraft:podzol");
		server.runCommand("fill 10 " + PAD_Y + " 10 11 " + PAD_Y + " 11 minecraft:podzol");
		server.runCommand("fill 7 " + PAD_Y + " 12 8 " + PAD_Y + " 13 minecraft:podzol");
		server.runCommand("fill 6 " + PAD_Y + " 10 7 " + PAD_Y + " 11 minecraft:podzol");
		server.runCommand("fill 4 " + PAD_Y + " 8 5 " + PAD_Y + " 9 minecraft:podzol");
		server.runCommand("fill 6 " + PAD_Y + " 6 7 " + PAD_Y + " 7 minecraft:podzol");
	}

	private static void fillChests(TestServerContext server) {
		// The four blocks form two double chests, so slots 0-26 are shared by
		// each pair.  Fill every slot; otherwise the cycle can run out of
		// bonemeal on an unlucky growth roll.
		for (int x = 8; x <= 9; x++) {
			for (int z = 8; z <= 9; z++) {
				for (int slot = 0; slot < 27; slot++) {
					server.runCommand("item replace block " + x + " " + CHEST_Y + " " + z
						+ " container." + slot + " with minecraft:bone_meal 64");
				}
			}
		}
	}

	private static void emptyChests(TestServerContext server) {
		// Clear the block entities in place.  Breaking the chest blocks would
		// spill every stack on the floor, and the player would vacuum it back
		// up -- which looks like the controller magically finding bonemeal in
		// "empty" chests.  Each half of a double chest owns its own 27 slots,
		// so all four blocks are cleared.
		for (int x = 8; x <= 9; x++) {
			for (int z = 8; z <= 9; z++) {
				server.runCommand("data merge block " + x + " " + CHEST_Y + " " + z + " {Items:[]}");
			}
		}
	}

	private static void giveKit(TestServerContext server, boolean creative) {
		server.runCommand("clear @a");
		server.runCommand("give @a minecraft:spruce_sapling " + (creative ? 64 : 128));
		server.runCommand("give @a minecraft:netherite_axe 1");
		if (!creative) {
			server.runCommand("give @a minecraft:spruce_log 64");
			server.runCommand("give @a minecraft:stick 32");
		}
		server.runCommand("effect give @a minecraft:saturation 100 255 true");
	}

	private static void plantSaplings(TestServerContext server) {
		for (PlantSpot spot : List.copyOf(SpruceFarmClient.controller().spots())) {
			BlockPos base = spot.saplingPos;
			server.runCommand("setblock " + base.getX() + " " + base.getY() + " " + base.getZ()
				+ " minecraft:spruce_sapling");
		}
	}

	private static void emulateAxe(TestServerContext server) {
		FarmController controller = SpruceFarmClient.controller();
		List<PlantSpot> spots = List.copyOf(controller.spots());
		for (PlantSpot spot : spots) {
			BlockPos base = spot.saplingPos;
			server.runCommand("fill " + (base.getX() - 2) + " " + (base.getY() + 1) + " " + (base.getZ() - 2)
				+ " " + (base.getX() + 2) + " " + (base.getY() + 48) + " " + (base.getZ() + 2) + " minecraft:air");
		}
	}

	private static void waitPlanted(ClientGameTestContext context, TestServerContext server, int expected, int timeout) {
		int waited = 0;
		while (waited < timeout) {
			clearLeaves(server);
			if (context.computeOnClient(mc -> {
				FarmController live = SpruceFarmClient.controller();
				failOnError(live);
				return live.spots().size() == expected
					&& live.spots().stream().allMatch(spot -> spot.state == com.sprucefarm.SpotState.PLANTED);
			})) {
				return;
			}
			context.waitTicks(10);
			waited += 10;
		}
		throw new AssertionError("Timed out waiting for " + expected + " planted spots");
	}

	private static void waitGrown(ClientGameTestContext context, TestServerContext server, int expected, int timeout) {
		int waited = 0;
		while (waited < timeout) {
			clearLeaves(server);
			if (context.computeOnClient(mc -> {
				FarmController live = SpruceFarmClient.controller();
				failOnError(live);
				// Use the growth counter, not the current block states: the
				// controller starts harvesting the instant the last tree grows,
				// so an all-GROWN snapshot may only exist for one tick.
				return live.spots().size() == expected
					&& live.cycleStats().grown >= expected;
			})) {
				return;
			}
			context.waitTicks(10);
			waited += 10;
		}
		throw new AssertionError("Timed out waiting for " + expected + " grown spots");
	}

	/**
	 * The test axe stand-in removes logs only, while the real server plugin
	 * removes the canopy with them.  Sweeping leaves keeps the other phases
	 * focused on farming mechanics; the accepted "leaf blocks a ray" stop has
	 * its own phase at the end.
	 */
	private static void clearLeaves(TestServerContext server) {
		server.runCommand("fill 1 102 1 16 115 16 minecraft:air replace minecraft:spruce_leaves");
	}

	private static void waitHarvested(ClientGameTestContext context, int expected, int timeout) {
		context.waitFor(mc -> {
			FarmController live = SpruceFarmClient.controller();
			failOnError(live);
			return live.totalStats().harvested >= expected;
		}, timeout);
	}

	private static void failOnError(FarmController controller) {
		if (controller.stage() == FarmController.Stage.ERROR) {
			throw new AssertionError("Farm error: " + controller.error());
		}
	}
}
