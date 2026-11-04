package com.sprucefarm;

import net.minecraft.core.BlockPos;

public final class PlantSpot {
	public final BlockPos grassPos;
	public final BlockPos saplingPos;
	public int padId;
	public SpotState state = SpotState.EMPTY;
	public String note = "";
	public int plantAttempts;
	public int growAttempts;
	public int visionFailures;
	public int obstructionClears;
	public int nextAttemptTick;
	/** Server sequence at the last placement click; -1 when not awaiting confirmation. */
	public long plantClickSequence = -1L;
	/** Client tick at the last placement click. */
	public int plantClickTick;
	/** True only after a server block update says the sapling is really there. */
	public boolean serverConfirmedPlant;
	/** Number of placements the server explicitly rolled back at this spot. */
	public int serverRejections;
	/** True while the client still shows a prediction the server already rejected. */
	public boolean serverRejectPending;
	/** A tree the axe could not finish, or a spot hidden behind a sign box
	 * that the server refuses to place through.  It is ignored for the rest
	 * of the session (a Rescan rebuilds the spots) so one bad spot can never
	 * stop the farm. */
	public boolean abandoned;
	/** No direct top-face camera hit from the centre: the soil is covered by
	 * the sign box, so a placement needs a through-sign click. */
	public boolean hidden;
	/** Diagnostics for the last placement attempt. */
	public double lastHitDistance;
	public String lastHitFace = "";

	public PlantSpot(BlockPos grassPos, int padId) {
		this.grassPos = grassPos.immutable();
		this.saplingPos = this.grassPos.above();
		this.padId = padId;
	}

	public void resetCycle() {
		plantAttempts = 0;
		growAttempts = 0;
		visionFailures = 0;
		obstructionClears = 0;
		nextAttemptTick = 0;
		plantClickSequence = -1L;
		plantClickTick = 0;
		serverConfirmedPlant = false;
		serverRejections = 0;
		serverRejectPending = false;
	}
}
