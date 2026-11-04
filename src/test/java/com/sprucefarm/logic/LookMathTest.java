package com.sprucefarm.logic;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LookMathTest {
	private static final double EPSILON = 0.001D;

	@Test
	void yawFacesCardinalDirections() {
		assertEquals(0.0F, LookMath.yawTo(0.0D, 0.0D, 0.0D, 1.0D), EPSILON);
		assertEquals(90.0F, LookMath.yawTo(0.0D, 0.0D, -1.0D, 0.0D), EPSILON);
		assertEquals(180.0F, Math.abs(LookMath.yawTo(0.0D, 0.0D, 0.0D, -1.0D)), EPSILON);
		assertEquals(-90.0F, LookMath.yawTo(0.0D, 0.0D, 1.0D, 0.0D), EPSILON);
	}

	@Test
	void pitchIsNegativeWhenLookingUp() {
		assertEquals(-45.0F, LookMath.pitchTo(0.0D, 1.0D, 0.0D, 1.0D, 2.0D, 0.0D), EPSILON);
		assertEquals(45.0F, LookMath.pitchTo(0.0D, 1.0D, 0.0D, 1.0D, 0.0D, 0.0D), EPSILON);
		assertEquals(0.0F, LookMath.pitchTo(0.0D, 1.0D, 0.0D, 1.0D, 1.0D, 0.0D), EPSILON);
	}

	@Test
	void reachUsesEuclideanDistance() {
		assertTrue(LookMath.withinReach(0.0D, 0.0D, 0.0D, 3.0D, 0.0D, 0.0D, 4.5D));
		assertTrue(LookMath.withinReach(0.0D, 0.0D, 0.0D, 4.4D, 0.0D, 0.0D, 4.5D));
		assertFalse(LookMath.withinReach(0.0D, 0.0D, 0.0D, 4.6D, 0.0D, 0.0D, 4.5D));
	}

	@Test
	void wrapNormalisesAngles() {
		assertEquals(-90.0F, LookMath.wrapDegrees(270.0F), EPSILON);
		assertEquals(10.0F, LookMath.wrapDegrees(370.0F), EPSILON);
		assertEquals(-170.0F, LookMath.wrapDegrees(190.0F), EPSILON);
	}
}
