package com.sprucefarm;

public enum SpotState {
	/** Grass block with air above: a sapling can be planted. */
	EMPTY,
	/** A spruce sapling is present. */
	PLANTED,
	/** A spruce log is present: the tree is ready to harvest. */
	GROWN,
	/** Something else occupies the spot or the view is obstructed. */
	BLOCKED
}
