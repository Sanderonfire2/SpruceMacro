package com.sprucefarm;

public final class FarmStats {
	public int planted;
	public int grown;
	public int harvested;
	/** Extra fells after the Shard Axe left logs standing. */
	public int extraFells;
	/** Trees skipped because the axe could not finish them. */
	public int skippedTrees;

	public void reset() {
		planted = 0;
		grown = 0;
		harvested = 0;
		extraFells = 0;
		skippedTrees = 0;
	}

	public FarmStats copy() {
		FarmStats copy = new FarmStats();
		copy.planted = planted;
		copy.grown = grown;
		copy.harvested = harvested;
		copy.extraFells = extraFells;
		copy.skippedTrees = skippedTrees;
		return copy;
	}
}
