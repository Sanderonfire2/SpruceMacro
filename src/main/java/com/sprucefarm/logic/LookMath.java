package com.sprucefarm.logic;

/**
 * Pure math for aiming the local player at a world position.
 *
 * <p>Minecraft yaw is 0 when facing south (+Z), 90 when facing west (-X),
 * -90 when facing east (+X) and 180/-180 when facing north (-Z). Pitch is
 * negative when looking up.</p>
 */
public final class LookMath {
	private LookMath() {
	}

	public static float yawTo(double eyeX, double eyeZ, double targetX, double targetZ) {
		double dx = targetX - eyeX;
		double dz = targetZ - eyeZ;
		return (float) Math.toDegrees(Math.atan2(-dx, dz));
	}

	public static float pitchTo(double eyeX, double eyeY, double eyeZ,
								double targetX, double targetY, double targetZ) {
		double dx = targetX - eyeX;
		double dy = targetY - eyeY;
		double dz = targetZ - eyeZ;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		return (float) -Math.toDegrees(Math.atan2(dy, horizontal));
	}

	public static boolean withinReach(double eyeX, double eyeY, double eyeZ,
									  double targetX, double targetY, double targetZ,
									  double reach) {
		double dx = targetX - eyeX;
		double dy = targetY - eyeY;
		double dz = targetZ - eyeZ;
		return dx * dx + dy * dy + dz * dz <= reach * reach;
	}

	public static float wrapDegrees(float degrees) {
		float wrapped = degrees % 360.0F;
		if (wrapped >= 180.0F) {
			wrapped -= 360.0F;
		}
		if (wrapped < -180.0F) {
			wrapped += 360.0F;
		}
		return wrapped;
	}
}
