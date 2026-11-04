package com.sprucefarm;

import com.sprucefarm.logic.LookMath;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;
import java.util.function.Predicate;

public final class InteractionHelper {
	private static final double MAX_BLOCK_REACH = 4.5D;
	private static final double MAX_BLOCK_REACH_SQR = MAX_BLOCK_REACH * MAX_BLOCK_REACH + 1.0E-4D;
	private static final Random AIM_RANDOM = new Random();
	private static Object pendingInventoryMenu;
	private static int pendingInventoryState = -1;

	private InteractionHelper() {
	}

	public enum Ensure {
		READY,
		BUSY,
		MISSING
	}

	public record EnsureResult(Ensure state, int slot, String detail) {
	}

	public static Vec3 aimTop(BlockPos pos) {
		return new Vec3(pos.getX() + 0.5D, pos.getY() + 0.99D, pos.getZ() + 0.5D);
	}

	public static Vec3 aimCenter(BlockPos pos) {
		return Vec3.atCenterOf(pos);
	}

	/**
	 * Turns towards the target by a limited step per tick and lets vanilla's
	 * normal LocalPlayer.sendPosition() send the rotation packet.  Returns true
	 * once the rotation is already correct from an earlier tick, so callers
	 * must still wait one tick before acting: the movement packet for the tick
	 * that set the final rotation has to reach the server first.
	 */
	/**
	 * Returns the hit result the player's actual crosshair produces, but only
	 * when it lands on the requested block.  This is what a real right-click
	 * sends; a synthetic raycast hit can be cancelled by a strict anti-cheat
	 * even when the block is visible.
	 */
	public static BlockHitResult cameraHit(Minecraft client, BlockPos target) {
		if (client.player == null || client.level == null) {
			return null;
		}
		Vec3 eye = client.player.getEyePosition();
		Vec3 end = eye.add(client.player.getViewVector(1.0F).scale(6.0D));
		BlockHitResult hit = client.level.clip(new ClipContext(
			eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player
		));
		if (hit == null || hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) {
			return null;
		}
		if (eye.distanceToSqr(hit.getLocation()) > MAX_BLOCK_REACH_SQR) {
			return null;
		}
		return hit;
	}

	/** The exact look angles the mod aimed at. */
	public record Aim(float yaw, float pitch) {
	}

	/**
	 * Turns the player towards a point immediately, but sends no packet of its
	 * own.  Vanilla puts the new yaw/pitch into this tick's normal movement
	 * packet, so the server has the rotation before the next tick's click.
	 *
	 * <p>Never replace this with a hand-built ServerboundMovePlayerPacket.Rot:
	 * that is what the server kicks as {@code Invalid sequence}.  The rotation
	 * and the interaction arrive in the same tick, and vanilla then sends a
	 * second rotation packet at the end of that tick as well, so the server
	 * sees two movement packets around one action instead of a look followed by
	 * a click.  The caller must still wait for a later tick before using an
	 * item (see FarmController's pending action).</p>
	 */
	public static Aim snapLook(LocalPlayer player, Vec3 target) {
		Vec3 eye = player.getEyePosition();
		float yaw = LookMath.yawTo(eye.x, eye.z, target.x, target.z);
		float pitch = Mth.clamp(
			LookMath.pitchTo(eye.x, eye.y, eye.z, target.x, target.y, target.z), -90.0F, 90.0F
		);
		player.setYRot(yaw);
		player.setXRot(pitch);
		return new Aim(yaw, pitch);
	}

	/** True while the camera still points exactly where the action was aimed. */
	public static boolean rotationMatches(LocalPlayer player, Aim aim) {
		return Math.abs(Mth.wrapDegrees(player.getYRot() - aim.yaw())) < 0.05F
			&& Math.abs(player.getXRot() - aim.pitch()) < 0.05F;
	}

	/** Exact turn for container opening; the click follows on the next tick. */
	public static void lookNow(LocalPlayer player, Vec3 target) {
		snapLook(player, target);
	}

	/**
	 * Slow-mode aim: turns in human-sized steps and reports true once the
	 * final rotation was already sent in an earlier tick's movement packet.
	 */
	public static boolean aim(LocalPlayer player, Vec3 target) {
		Vec3 eye = player.getEyePosition();
		float yaw = LookMath.yawTo(eye.x, eye.z, target.x, target.z);
		float pitch = LookMath.pitchTo(eye.x, eye.y, eye.z, target.x, target.y, target.z);
		float yawDelta = Mth.wrapDegrees(yaw - player.getYRot());
		float pitchDelta = pitch - player.getXRot();
		if (Math.abs(yawDelta) < 0.5F && Math.abs(pitchDelta) < 0.5F) {
			boolean changed = Math.abs(yawDelta) > 0.01F || Math.abs(pitchDelta) > 0.01F;
			player.setYRot(yaw);
			player.setXRot(pitch);
			return !changed;
		}
		// A human can move the mouse quickly, but a single 180-degree snap on
		// the first placement is a common macro/aimbot signature.  Randomising
		// the step avoids the repeating "same delta every placement" pattern
		// that duplicate-rotation checks look for.
		float step = 8.0F + AIM_RANDOM.nextFloat() * 16.0F;
		player.setYRot(player.getYRot() + Mth.clamp(yawDelta, -step, step));
		player.setXRot(Mth.clamp(player.getXRot() + Mth.clamp(pitchDelta, -step, step), -90.0F, 90.0F));
		return false;
	}

	/**
	 * Casts to points on the top face and returns the closest legal hit.  The
	 * closest point matters: the centre of a far pad can be more than the
	 * client's 4.5-block reach away even though the block's nearest edge is
	 * inside it.  A centre-first click there is not a vanilla interaction and
	 * strict servers cancel it as a ghost block.
	 */
	public static BlockHitResult raycastTop(Minecraft client, BlockPos target) {
		if (client.player == null) {
			return null;
		}
		Vec3 eye = client.player.getEyePosition();
		BlockHitResult best = null;
		double bestDistance = Double.MAX_VALUE;
		double[][] samples = {
			{0.5D, 0.5D}, {0.25D, 0.25D}, {0.75D, 0.25D},
			{0.25D, 0.75D}, {0.75D, 0.75D}, {0.5D, 0.2D},
			{0.5D, 0.8D}, {0.2D, 0.5D}, {0.8D, 0.5D},
			{0.5D, 0.08D}, {0.5D, 0.92D}, {0.08D, 0.5D}, {0.92D, 0.5D}
		};
		for (double[] sample : samples) {
			BlockHitResult hit = topSample(client, target, eye, sample[0], sample[1]);
			if (hit == null) {
				continue;
			}
			double distance = eye.distanceToSqr(hit.getLocation());
			if (distance < bestDistance) {
				best = hit;
				bestDistance = distance;
			}
		}
		// Saplings on the neighbouring spots have a small cross outline, so a
		// dense sweep of the top face often finds a legitimate visible point
		// even when the centre ray is blocked.
		for (int ix = 1; ix <= 9; ix++) {
			for (int iz = 1; iz <= 9; iz++) {
				BlockHitResult hit = topSample(client, target, eye, ix / 10.0D, iz / 10.0D);
				if (hit == null) {
					continue;
				}
				double distance = eye.distanceToSqr(hit.getLocation());
				if (distance < bestDistance) {
					best = hit;
					bestDistance = distance;
				}
			}
		}
		// A sapling planted on the diagonal spot of the same 2x2 pad can cover
		// the whole coarse grid while leaving a sliver of the top face clear at
		// the edge.  Try the exact corners and the point nearest the eye (where
		// a player aiming from this side of the pad would actually click).
		double nearX = Mth.clamp(eye.x - target.getX(), 0.02D, 0.98D);
		double nearZ = Mth.clamp(eye.z - target.getZ(), 0.02D, 0.98D);
		double[][] edges = {
			{nearX, nearZ}, {0.02D, 0.02D}, {0.98D, 0.02D}, {0.02D, 0.98D},
			{0.98D, 0.98D}, {0.5D, 0.02D}, {0.5D, 0.98D}, {0.02D, 0.5D}, {0.98D, 0.5D}
		};
		for (double[] sample : edges) {
			BlockHitResult hit = topSample(client, target, eye, sample[0], sample[1]);
			if (hit == null) {
				continue;
			}
			double distance = eye.distanceToSqr(hit.getLocation());
			if (distance < bestDistance) {
				best = hit;
				bestDistance = distance;
			}
		}
		return best;
	}

	private static BlockHitResult topSample(Minecraft client, BlockPos target, Vec3 eye, double x, double z) {
		Vec3 aim = new Vec3(target.getX() + x, target.getY() + 0.99D, target.getZ() + z);
		BlockHitResult hit = clip(client, target, aim);
		if (hit == null || hit.getDirection() != Direction.UP) {
			return null;
		}
		return hit;
	}

	/** Casts to interior points of a block and returns the closest legal hit. */
	public static BlockHitResult raycastBlock(Minecraft client, BlockPos target) {
		if (client.player == null) {
			return null;
		}
		Vec3 eye = client.player.getEyePosition();
		BlockHitResult best = null;
		double bestDistance = Double.MAX_VALUE;
		double[][] samples = {
			{0.5D, 0.5D, 0.5D}, {0.5D, 0.5D, 0.2D}, {0.5D, 0.5D, 0.8D},
			{0.2D, 0.5D, 0.5D}, {0.8D, 0.5D, 0.5D}, {0.5D, 0.2D, 0.5D},
			{0.5D, 0.8D, 0.5D}, {0.2D, 0.25D, 0.2D}, {0.8D, 0.25D, 0.8D}
		};
		for (double[] sample : samples) {
			Vec3 aim = new Vec3(
				target.getX() + sample[0],
				target.getY() + sample[1],
				target.getZ() + sample[2]
			);
			BlockHitResult hit = clip(client, target, aim);
			if (hit != null) {
				double distance = eye.distanceToSqr(hit.getLocation());
				if (distance < bestDistance) {
					best = hit;
					bestDistance = distance;
				}
			}
		}
		// A neighbouring sapling or trunk can cover the centre of a target
		// while leaving a sliver of a face visible.  Sapling shapes start at
		// 0.25 and end at 0.75 inside the block, so sample those exact edges:
		// the south/north corner of a sapling one block away is often the only
		// clear line of sight.
		double[] grid = {0.1D, 0.25D, 0.3D, 0.5D, 0.7D, 0.75D, 0.9D};
		for (double x : grid) {
			for (double y : grid) {
				for (double z : grid) {
					BlockHitResult hit = clip(client, target, new Vec3(
						target.getX() + x, target.getY() + y, target.getZ() + z
					));
					if (hit != null) {
						double distance = eye.distanceToSqr(hit.getLocation());
						if (distance < bestDistance) {
							best = hit;
							bestDistance = distance;
						}
					}
				}
			}
		}
		return best;
	}

	/**
	 * The server accepts a block interaction when the eye is within this
	 * block's interaction range plus one block of its bounding box.  This is
	 * the reach limit for both a visible click and the optional direct
	 * fallback; it is not by itself a line-of-sight check.
	 */
	public static boolean canInteract(Minecraft client, BlockPos pos) {
		// Use the real block interaction range with only a tiny tolerance.
		// The server also adds a one-block padding to the block's bounding
		// box, but strict anti-cheats measure the actual hit distance; using
		// the server's padded range here would let the mod pick outer spots
		// that the server cancels as too far.
		return client.player != null && client.player.isWithinBlockInteractionRange(pos, 0.05D);
	}

	/**
	 * Builds the hit result a normal client click would produce if the camera
	 * could see the requested face.  Only used when directFallback is enabled;
	 * strict mode never sends a hit the eye cannot see.
	 */
	public static BlockHitResult directHit(BlockPos pos, Direction face) {
		double x = pos.getX() + 0.5D;
		double y = pos.getY() + 0.5D;
		double z = pos.getZ() + 0.5D;
		switch (face) {
			case UP -> y = pos.getY() + 0.99D;
			case DOWN -> y = pos.getY() + 0.01D;
			case NORTH -> z = pos.getZ() + 0.01D;
			case SOUTH -> z = pos.getZ() + 0.99D;
			case WEST -> x = pos.getX() + 0.01D;
			case EAST -> x = pos.getX() + 0.99D;
		}
		return new BlockHitResult(new Vec3(x, y, z), face, pos.immutable(), false);
	}

	/**
	 * A direct hit near the eye-facing edge, if that point is inside the real
	 * 4.5-block reach.  This keeps the fallback from sending a legitimate hit
	 * location the client's own crosshair could never have produced.
	 */
	public static BlockHitResult directHitNear(Minecraft client, BlockPos pos, Direction face) {
		if (client.player == null) {
			return null;
		}
		Vec3 eye = client.player.getEyePosition();
		double x = Mth.clamp(eye.x, pos.getX() + 0.01D, pos.getX() + 0.99D);
		double y = Mth.clamp(eye.y, pos.getY() + 0.01D, pos.getY() + 0.99D);
		double z = Mth.clamp(eye.z, pos.getZ() + 0.01D, pos.getZ() + 0.99D);
		switch (face) {
			case UP -> y = pos.getY() + 0.99D;
			case DOWN -> y = pos.getY() + 0.01D;
			case NORTH -> z = pos.getZ() + 0.01D;
			case SOUTH -> z = pos.getZ() + 0.99D;
			case WEST -> x = pos.getX() + 0.01D;
			case EAST -> x = pos.getX() + 0.99D;
		}
		Vec3 hit = new Vec3(x, y, z);
		if (eye.distanceToSqr(hit) > MAX_BLOCK_REACH_SQR) {
			return null;
		}
		return new BlockHitResult(hit, face, pos.immutable(), false);
	}

	/**
	 * A top-face hit on {@code target} that a normal client can produce even
	 * though the crosshair cuts a plant or a sign first.  Saplings have no
	 * collision shape and a sign's board is a thin, non-solid outline, so
	 * neither can stop a placement: the click still lands on the soil behind
	 * them.  The usual offender is the diagonal sapling of the same 2x2 pad,
	 * planted a moment earlier by this sweep; a sign wall built over the
	 * chests is the other.
	 *
	 * <p>Points are sampled across the face rather than only at the corner
	 * nearest the eye: that corner is shared with the soil block under the
	 * covering sapling, and a ray to it grazes that block's corner, which made
	 * a single corner shot fail exactly where the sapling sits diagonally.</p>
	 *
	 * <p>{@code allowSigns} is the user's interaction setting.  A solid block
	 * in front always rejects the point, so turning signs off only removes the
	 * sign bypass and never sends a through-wall placement.</p>
	 */
	public static BlockHitResult plantHitThroughPlants(Minecraft client, BlockPos target, boolean allowSigns) {
		if (client.player == null || client.level == null) {
			return null;
		}
		Vec3 eye = client.player.getEyePosition();
		// Pulled in from the edges so the ray clears the neighbouring soil.
		double[] steps = {0.5D, 0.7D, 0.3D, 0.8D, 0.2D, 0.85D, 0.15D};
		BlockHitResult best = null;
		double bestDistance = Double.MAX_VALUE;
		for (double x : steps) {
			for (double z : steps) {
				Vec3 aim = new Vec3(target.getX() + x, target.getY() + 0.99D, target.getZ() + z);
				double distance = eye.distanceToSqr(aim);
				if (distance > MAX_BLOCK_REACH_SQR) {
					continue;
				}
				if (!outlineIsPlant(client, target, aim, allowSigns)) {
					continue;
				}
				if (!noSolidInFront(client, aim)) {
					continue;
				}
				BlockHitResult hit = new BlockHitResult(aim, Direction.UP, target.immutable(), false);
				if (distance < bestDistance) {
					best = hit;
					bestDistance = distance;
				}
			}
		}
		return best;
	}

	/**
	 * True when the ray to this hit point only crosses plants (and, when
	 * allowed, signs): nothing solid stands in the way, and the first block the
	 * outline ray meets is a plant or sign (so the plain crosshair check cannot
	 * see the target block itself).
	 */
	public static boolean hitCrossesOnlyPlants(Minecraft client, BlockHitResult hit, boolean allowSigns) {
		if (client.player == null || client.level == null) {
			return false;
		}
		return outlineIsPlant(client, hit.getBlockPos(), hit.getLocation(), allowSigns);
	}

	/**
	 * True when the crosshair meets a plant or a sign before the target block.
	 * Both are non-solid outlines, so the target face behind them is a legal
	 * click even though the plain crosshair ray stops at the covering shape.
	 */
	private static boolean outlineIsPlant(Minecraft client, BlockPos target, Vec3 aim, boolean allowSigns) {
		Vec3 eye = client.player.getEyePosition();
		BlockHitResult outline = client.level.clip(new ClipContext(
			eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player
		));
		if (outline == null || outline.getType() != HitResult.Type.BLOCK
			|| outline.getBlockPos().equals(target)) {
			return false;
		}
		BlockState state = client.level.getBlockState(outline.getBlockPos());
		return state.is(BlockTags.SAPLINGS) || (allowSigns && isSign(state));
	}

	/**
	 * A legal face hit on {@code target} when a sign wall covers it.  The
	 * client's own crosshair cannot reach a covered chest or trunk, but a sign
	 * is non-solid, so a player can click straight through it.  Only plants
	 * and signs may sit in front: the first solid block before the aim point
	 * rejects the hit, so this never becomes a through-wall interaction.
	 *
	 * <p>The hit is a real point on the eye-facing face inside the server's
	 * 4.5-block reach, and the camera is aimed at that point before the click
	 * is sent, so the packet still looks like a normal click to a server that
	 * raycasts the aim.  The thin sign board is simply not an obstacle.</p>
	 */
	public static BlockHitResult signBlockedHit(Minecraft client, BlockPos target) {
		if (client.player == null || client.level == null || !canInteract(client, target)) {
			return null;
		}
		BlockState targetState = client.level.getBlockState(target);
		if (isSign(targetState)) {
			return null;
		}
		Vec3 eye = client.player.getEyePosition();
		Vec3 aim;
		if (targetState.is(BlockTags.SAPLINGS)) {
			// A sapling's cross is a thin shape in the middle of its cell.  Aim
			// at the centre so the ray really meets the plant after the sign.
			aim = Vec3.atCenterOf(target);
		} else {
			Vec3 centre = Vec3.atCenterOf(target);
			double dx = eye.x - centre.x;
			double dy = eye.y - centre.y;
			double dz = eye.z - centre.z;
			double ax = Math.abs(dx);
			double ay = Math.abs(dy);
			double az = Math.abs(dz);
			Direction face;
			if (ay >= ax && ay >= az) {
				face = dy >= 0.0D ? Direction.UP : Direction.DOWN;
			} else if (ax >= az) {
				face = dx >= 0.0D ? Direction.EAST : Direction.WEST;
			} else {
				face = dz >= 0.0D ? Direction.SOUTH : Direction.NORTH;
			}
			BlockHitResult near = directHitNear(client, target, face);
			if (near == null) {
				return null;
			}
			aim = near.getLocation();
		}
		BlockHitResult hit = plantsOrSignsBefore(client, target, aim);
		if (hit == null || !noSolidInFront(client, hit.getLocation())) {
			return null;
		}
		return hit;
	}

	private static boolean isSign(BlockState state) {
		return state.is(BlockTags.ALL_SIGNS);
	}

	/**
	 * Walks the outline ray from the eye to the aim, stepping past every thin
	 * plant/sign board, and returns the real hit on the target.  Null means a
	 * different block was in the way or the target was never reached.
	 */
	private static BlockHitResult plantsOrSignsBefore(Minecraft client, BlockPos target, Vec3 aim) {
		Vec3 eye = client.player.getEyePosition();
		Vec3 start = eye;
		double total = eye.distanceTo(aim);
		Vec3 direction = aim.subtract(eye).normalize();
		for (int depth = 0; depth < 16; depth++) {
			BlockHitResult hit = client.level.clip(new ClipContext(
				start, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player
			));
			if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
				return null;
			}
			if (hit.getBlockPos().equals(target)) {
				return hit;
			}
			BlockState state = client.level.getBlockState(hit.getBlockPos());
			if (!state.is(BlockTags.SAPLINGS) && !isSign(state)) {
				return null;
			}
			if (eye.distanceTo(hit.getLocation()) >= total - 1.0E-4D) {
				return null;
			}
			// A board is only 1/16 thick, but a shallow ray can stay inside
			// its box for several centimetres.  Step past the whole sign block
			// so the next clip cannot keep hitting the same board.
			start = exitBlock(hit.getLocation(), direction, hit.getBlockPos());
			BlockPos after = BlockPos.containing(start);
			if (!after.equals(target)) {
				BlockState afterState = client.level.getBlockState(after);
				// Starting just inside the next block must not let a solid one
				// be skipped by the outline clip.  Only air, water, plants and
				// signs may follow a skipped board.
				if (!afterState.isAir() && !afterState.is(BlockTags.SAPLINGS) && !isSign(afterState)
					&& !afterState.getCollisionShape(client.level, after).isEmpty()) {
					return null;
				}
			}
		}
		return null;
	}

	/** Point just outside {@code pos} along {@code direction} from {@code start}. */
	private static Vec3 exitBlock(Vec3 start, Vec3 direction, BlockPos pos) {
		double minX = pos.getX();
		double minY = pos.getY();
		double minZ = pos.getZ();
		double maxX = minX + 1.0D;
		double maxY = minY + 1.0D;
		double maxZ = minZ + 1.0D;
		double exit = Double.MAX_VALUE;
		if (direction.x > 1.0E-7D) {
			exit = Math.min(exit, (maxX - start.x) / direction.x);
		} else if (direction.x < -1.0E-7D) {
			exit = Math.min(exit, (minX - start.x) / direction.x);
		}
		if (direction.y > 1.0E-7D) {
			exit = Math.min(exit, (maxY - start.y) / direction.y);
		} else if (direction.y < -1.0E-7D) {
			exit = Math.min(exit, (minY - start.y) / direction.y);
		}
		if (direction.z > 1.0E-7D) {
			exit = Math.min(exit, (maxZ - start.z) / direction.z);
		} else if (direction.z < -1.0E-7D) {
			exit = Math.min(exit, (minZ - start.z) / direction.z);
		}
		if (exit == Double.MAX_VALUE || exit < 0.0D) {
			exit = 0.0D;
		}
		return start.add(direction.scale(exit + 0.01D));
	}

	/**
	 * True when no solid block is hit before the aim point.  A ray that grazes
	 * a neighbouring soil corner within 0.1 of the aim is allowed: that corner
	 * is the top-face point itself, not a wall in front of it.
	 */
	private static boolean noSolidInFront(Minecraft client, Vec3 aim) {
		Vec3 eye = client.player.getEyePosition();
		BlockHitResult solid = client.level.clip(new ClipContext(
			eye, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, client.player
		));
		if (solid == null || solid.getType() != HitResult.Type.BLOCK) {
			return true;
		}
		return solid.getLocation().distanceToSqr(aim) <= 0.01D;
	}

	/**
	 * Returns the first block between the eye and the target when the target
	 * itself is not directly visible, or null when the target is the first hit.
	 */
	public static BlockHitResult blockingHit(Minecraft client, BlockPos target) {
		if (client.player == null || client.level == null) {
			return null;
		}
		if (!canInteract(client, target)) {
			return null;
		}
		Vec3 eye = client.player.getEyePosition();
		BlockHitResult hit = client.level.clip(new ClipContext(
			eye, Vec3.atCenterOf(target), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player
		));
		if (hit == null || hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(target)) {
			return null;
		}
		return hit;
	}

	private static BlockHitResult clip(Minecraft client, BlockPos target, Vec3 aim) {
		if (client.player == null || client.level == null) {
			return null;
		}
		if (!canInteract(client, target)) {
			return null;
		}
		Vec3 eye = client.player.getEyePosition();
		BlockHitResult hit = client.level.clip(new ClipContext(
			eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player
		));
		if (hit == null || hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target)) {
			return null;
		}
		if (eye.distanceToSqr(hit.getLocation()) > MAX_BLOCK_REACH_SQR) {
			return null;
		}
		return hit;
	}

	/**
	 * Re-asserts the selected hotbar slot on the server immediately before an
	 * interaction.  The server uses its own held slot for the click, so a
	 * selection the client made locally (or one the server missed during an
	 * inventory swap) would otherwise make the server place whatever it has
	 * in hand.  The client still predicts a sapling, which is the "ghost block"
	 * that disappears on the next server block update.
	 */
	public static void syncHeldSlot(Minecraft client) {
		if (client.player instanceof LocalPlayer localPlayer) {
			localPlayer.connection.send(new ServerboundSetCarriedItemPacket(
				client.player.getInventory().getSelectedSlot()
			));
		}
	}

	public static InteractionResult useOn(Minecraft client, BlockHitResult hit) {
		if (client.gameMode == null || client.player == null) {
			return InteractionResult.PASS;
		}
		syncHeldSlot(client);
		InteractionResult result = client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
		if (result.consumesAction()) {
			client.player.swing(InteractionHand.MAIN_HAND);
		}
		return result;
	}

	public static void startBreaking(Minecraft client, BlockPos pos) {
		if (client.gameMode == null || client.player == null) {
			return;
		}
		syncHeldSlot(client);
		client.gameMode.startDestroyBlock(pos, Direction.UP);
		client.player.swing(InteractionHand.MAIN_HAND);
	}

	public static void continueBreaking(Minecraft client, BlockPos pos) {
		if (client.gameMode == null || client.player == null) {
			return;
		}
		syncHeldSlot(client);
		client.gameMode.continueDestroyBlock(pos, Direction.UP);
		client.player.swing(InteractionHand.MAIN_HAND);
	}

	public static void stopBreaking(Minecraft client) {
		if (client.gameMode != null) {
			client.gameMode.stopDestroyBlock();
		}
	}

	public static void selectHotbar(Minecraft client, int slot) {
		if (client.player == null || slot < 0 || slot >= InventoryHelper.HOTBAR_SIZE) {
			return;
		}
		client.player.getInventory().setSelectedSlot(slot);
		ServerState.recordHeldSlot(slot);
		if (client.player instanceof LocalPlayer localPlayer) {
			localPlayer.connection.send(new ServerboundSetCarriedItemPacket(slot));
		}
	}

	/**
	 * Makes sure the main hand holds an item matching the predicate. Moving an
	 * item from the main inventory into the hotbar costs one action, so BUSY is
	 * returned and the caller simply retries on its next action tick.
	 */
	public static EnsureResult ensureItem(Minecraft client, Predicate<ItemStack> predicate, FarmConfig config) {
		if (client.player == null) {
			return new EnsureResult(Ensure.MISSING, -1, "No player");
		}
		LocalPlayer player = client.player;
		int selected = player.getInventory().getSelectedSlot();
		// A swap click is applied to the client inventory on the same tick it
		// is sent, and the server processes the packets in order, so there is
		// nothing to wait for here.
		pendingInventoryMenu = null;
		pendingInventoryState = -1;

		// The client inventory is the source of truth here.  Since 1.17 the
		// client applies its own container clicks locally and the server only
		// answers with a correction when it rejects one, so a mirrored
		// "server inventory" legitimately misses every accepted click and
		// must never be what decides whether an item is available.
		if (predicate.test(player.getInventory().getSelectedItem())) {
			return new EnsureResult(Ensure.READY, selected, "");
		}

		for (int slot = 0; slot < InventoryHelper.HOTBAR_SIZE; slot++) {
			if (predicate.test(player.getInventory().getItem(slot))) {
				selectHotbar(client, slot);
				return new EnsureResult(Ensure.READY, slot, "");
			}
		}

		int source = InventoryHelper.findSlot(player, predicate);
		if (source < 0) {
			return new EnsureResult(Ensure.MISSING, -1, "Item not found");
		}
		if (client.screen != null && !(client.screen instanceof FarmScreen)) {
			return new EnsureResult(Ensure.BUSY, -1, "A screen is open");
		}

		int target = chooseHotbarTarget(player, config);
		int menuSlot = InventoryHelper.menuSlotForInventoryIndex(source);
		if (menuSlot < 0 || client.gameMode == null) {
			return new EnsureResult(Ensure.MISSING, -1, "Invalid slot");
		}
		client.gameMode.handleInventoryMouseClick(
			InventoryMenu.CONTAINER_ID, menuSlot, target, ClickType.SWAP, player
		);
		ServerState.syncFromClient(player);
		selectHotbar(client, target);
		return new EnsureResult(Ensure.READY, target, "Swapping item into hotbar");
	}

	private static int chooseHotbarTarget(LocalPlayer player, FarmConfig config) {
		int selected = player.getInventory().getSelectedSlot();
		for (int slot = 0; slot < InventoryHelper.HOTBAR_SIZE; slot++) {
			if (player.getInventory().getItem(slot).isEmpty()) {
				return slot;
			}
		}
		for (int slot = 0; slot < InventoryHelper.HOTBAR_SIZE; slot++) {
			if (slot == selected) {
				continue;
			}
			ItemStack stack = player.getInventory().getItem(slot);
			if (InventoryHelper.isAxe(stack, config) || stack.is(net.minecraft.world.item.Items.BONE_MEAL)
				|| stack.is(net.minecraft.world.item.Items.SPRUCE_SAPLING)) {
				continue;
			}
			return slot;
		}
		return selected == 0 ? 1 : 0;
	}
}
