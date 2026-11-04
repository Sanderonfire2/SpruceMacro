package com.sprucefarm;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.Item;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FarmConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "sprucefarm.json";
	/** Bumped when the timing or sell pacing defaults change, so an old config
	 * file does not keep pinning the bot to the previous, slower cadence. */
	private static final int CURRENT_CONFIG_VERSION = 3;

	/** Written by {@link #save()}; stays 0 for a file that predates it, which
	 * is how {@link #load()} recognises an old timing configuration. */
	public int configVersion;

	/** Fast mode snaps the view to the next block and clicks on the next
	 * tick, then moves on after a short pause.  Turn it off to fall back to
	 * the slower, more human-paced rotation. */
	public boolean fastMode = true;
	/** Ticks to wait after the final snap before clicking.  1 keeps the
	 * rotation packet ahead of the use packet without a visible pause. */
	public int aimSettleTicks = 1;
	/** Slow-mode ticks between two world interactions.  Fast mode uses a
	 * one-tick cadence (ten actions per second) instead. */
	public int actionDelayTicks = 4;
	/** Ticks between two inventory clicks.  Server sell menus need time to
	 * acknowledge each click; fast mode trims the non-GUI waits. */
	public int guiDelayTicks = 4;
	/** Off by default: only visible interactions are sent.  Turning this on
	 * allows a direct hit when the camera ray is blocked; some server
	 * protection plugins reject that as a through-wall interaction. */
	public boolean directFallback = false;
	/** Click through the thin, non-solid boards of standing/wall signs.  This
	 * covers the usual setup where a sign box is built around the player on
	 * top of the centre chests.  Only signs are bypassed: the first solid
	 * block in front still rejects the click, and every hit is a real face
	 * point within the server's reach.  Turn this off if a strict anti-cheat
	 * rejects clicks on covered blocks. */
	public boolean interactThroughSigns = true;
	/** When the server rejects a placement, skip the problem spot (and every
	 * other spot that has no direct top-face line) and keep farming the rest
	 * instead of stopping.  A strict anti-cheat cannot be beaten by clicking
	 * harder; the skipped spots come back after the obstruction is removed
	 * and the layout is rescanned.  Keep the upper sign layer for leaf
	 * protection and accept the reduced spot count, or clear the sight line
	 * for the full farm. */
	public boolean skipHiddenSpots = true;
	/** Horizontal scan radius around the player, in blocks. */
	public int scanHorizontal = 5;
	/** Vertical scan range below the player's feet. */
	public int scanDown = 5;
	/** Vertical scan range above the player's feet. */
	public int scanUp = 3;
	/** Expected number of pads, used for validation and display. */
	public int expectedPads = 8;
	/** Expected number of planting spots (8 pads x 4). */
	public int expectedSpots = 32;
	/** Refuse to start when fewer than expectedSpots are detected. */
	public boolean requireFullLayout = true;
	/** Saplings that must never be sold. */
	public int keepSaplings = 64;
	/** Sell when this many inventory slots (or fewer) are still free.  The
	 * farm keeps working until the next harvest would otherwise be cut short,
	 * instead of opening the sell GUI after every single cycle. */
	public int sellFreeSlots = 6;
	/** Whole stacks quick-moved per tick while filling the sell GUI. */
	public int sellClicksPerTick = 6;
	/** Ticks between two batches of sell clicks.  The next batch goes out on
	 * the following tick; the verification after closing still catches a
	 * batch the server ignored. */
	public int sellClickDelayTicks = 0;
	/** Extra ticks before checking a sell batch.  The client applies its own
	 * container clicks immediately, so zero is enough. */
	public int sellVerifyTicks = 0;
	/** Extra fells allowed for a tree the Shard Axe only partly removed.
	 * Each pass breaks the new lowest remaining log from the same spot. */
	public int maxHarvestPasses = 10;
	/** Failed planting attempts per spot before the cycle is paused. */
	public int maxPlantAttempts = 12;
	/** Bonemeal applications per sapling before it is treated as obstructed. */
	public int maxBonemealPerSapling = 100;
	/** Ticks to wait for a server GUI to open. */
	public int guiOpenTimeoutTicks = 100;
	/** Ticks between two checks of the remaining trunk while the server's
	 * fell is still removing logs. */
	public int harvestSettleTicks = 20;
	/** Total ticks to wait for the server to finish felling one tree before
	 * the remaining logs are treated as a real leftover.  A plugin that
	 * removes a large spruce over several ticks must not be mistaken for a
	 * failed harvest. */
	public int fellFinishTimeoutTicks = 200;
	/** Ticks with no change in the remaining-log count before a stopped
	 * server fell is treated as a real leftover.  This must be longer than a
	 * plugin's delay between block removals. */
	public int fellQuietTicks = 60;

	/** Runtime override chosen in the panel; not persisted. */
	public transient Item axeOverride;

	public static FarmConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		FarmConfig config = new FarmConfig();
		if (Files.exists(path)) {
			try {
				FarmConfig loaded = GSON.fromJson(Files.readString(path), FarmConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (Exception exception) {
				SpruceFarmClient.LOGGER.warn("Could not read {}: {}", path, exception.getMessage());
			}
		}
		config.sanitise();
		boolean migrated = false;
		if (config.configVersion < CURRENT_CONFIG_VERSION) {
			// Timings written by an older build are the slow, human-paced
			// values.  Adopt the new fast defaults once; the Speed button in
			// the panel still overrides them afterwards.
			FarmConfig defaults = new FarmConfig();
			config.fastMode = defaults.fastMode;
			config.actionDelayTicks = defaults.actionDelayTicks;
			config.aimSettleTicks = defaults.aimSettleTicks;
			config.guiDelayTicks = defaults.guiDelayTicks;
			config.sellClicksPerTick = defaults.sellClicksPerTick;
			config.sellClickDelayTicks = defaults.sellClickDelayTicks;
			config.configVersion = CURRENT_CONFIG_VERSION;
			migrated = true;
		}
		if (!Files.exists(path) || migrated) {
			config.save();
		}
		return config;
	}

	public void save() {
		sanitise();
		configVersion = CURRENT_CONFIG_VERSION;
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this));
		} catch (IOException exception) {
			SpruceFarmClient.LOGGER.warn("Could not save {}: {}", path, exception.getMessage());
		}
	}

	private void sanitise() {
		actionDelayTicks = clamp(actionDelayTicks, 1, 20);
		guiDelayTicks = clamp(guiDelayTicks, 2, 20);
		aimSettleTicks = clamp(aimSettleTicks, 0, 10);
		scanHorizontal = clamp(scanHorizontal, 2, 8);
		scanDown = clamp(scanDown, 0, 8);
		scanUp = clamp(scanUp, 0, 6);
		expectedPads = clamp(expectedPads, 1, 64);
		expectedSpots = clamp(expectedSpots, 1, 256);
		keepSaplings = clamp(keepSaplings, 0, 640);
		sellFreeSlots = clamp(sellFreeSlots, 0, 36);
		sellClicksPerTick = clamp(sellClicksPerTick, 1, 9);
		sellClickDelayTicks = clamp(sellClickDelayTicks, 0, 10);
		sellVerifyTicks = clamp(sellVerifyTicks, 0, 10);
		maxHarvestPasses = clamp(maxHarvestPasses, 0, 40);
		maxPlantAttempts = clamp(maxPlantAttempts, 1, 100);
		maxBonemealPerSapling = clamp(maxBonemealPerSapling, 4, 1000);
		guiOpenTimeoutTicks = clamp(guiOpenTimeoutTicks, 20, 400);
		harvestSettleTicks = clamp(harvestSettleTicks, 5, 400);
		fellFinishTimeoutTicks = clamp(fellFinishTimeoutTicks, 20, 600);
		fellQuietTicks = clamp(fellQuietTicks, 20, 200);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

}
