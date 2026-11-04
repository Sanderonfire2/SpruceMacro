package com.sprucefarm.test.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.sprucefarm.test.TestSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlaceDebugMixin {
	@Inject(method = "useItemOn", at = @At("RETURN"), cancellable = true)
	private void sprucefarm$logSaplingUse(ServerPlayer player, Level level, ItemStack stack,
			InteractionHand hand, BlockHitResult hit, CallbackInfoReturnable<InteractionResult> cir) {
		if (stack.is(Items.BONE_MEAL) || player.getInventory().getSelectedItem().is(Items.BONE_MEAL)) {
			BlockPos clicked = hit.getBlockPos();
			System.out.println("[sprucefarm-debug] use bonemeal t=" + level.getGameTime()
				+ " at " + clicked + " clicked=" + level.getBlockState(clicked)
				+ " above=" + level.getBlockState(clicked.above())
				+ " selected=" + player.getInventory().getSelectedSlot()
				+ " hand=" + player.getInventory().getSelectedItem()
				+ " result=" + cir.getReturnValue());
			return;
		}
		InteractionResult result = cir.getReturnValue();
		BlockPos clicked = hit.getBlockPos();
		BlockPos above = clicked.relative(hit.getDirection());
		BlockState aboveState = level.getBlockState(above);
		boolean canReplace = aboveState.canBeReplaced(new net.minecraft.world.item.context.BlockPlaceContext(
			level, player, hand, stack, hit));
		BlockState placement = stack.getItem() instanceof BlockItem blockItem
			? blockItem.getBlock().defaultBlockState() : null;
		boolean canSurvive = placement != null && placement.canSurvive(level, above);
		boolean unobstructed = placement != null
			&& level.isUnobstructed(placement, above, CollisionContext.placementContext(player));
		System.out.println("[sprucefarm-debug] use sapling at " + clicked + " face=" + hit.getDirection()
			+ " hit=" + hit.getLocation() + " eye=" + player.getEyePosition()
			+ " selected=" + player.getInventory().getSelectedSlot()
			+ " hand=" + player.getMainHandItem()
			+ " clickedState=" + level.getBlockState(clicked)
			+ " above=" + above + "=" + aboveState
			+ " canReplace=" + canReplace
			+ " canSurvive=" + canSurvive
			+ " unobstructed=" + unobstructed
			+ " cooldown=" + player.getCooldowns().isOnCooldown(stack)
			+ " inRange=" + player.isWithinBlockInteractionRange(clicked, 1.0)
			+ " result=" + result);

		// Claim / ghost-support emulation: a spot the scan sees clearly is
		// still refused by the server.  The client keeps the predicted
		// sapling until the block correction arrives, exactly like the real
		// "server rejected every sapling" case.
		if (TestSupport.rejectSpot != null && stack.is(Items.SPRUCE_SAPLING)
			&& result != null && result.consumesAction()) {
			BlockPos placed = clicked.relative(hit.getDirection());
			if (placed.equals(TestSupport.rejectSpot)
				&& level.getBlockState(placed).is(Blocks.SPRUCE_SAPLING)) {
				level.removeBlock(placed, false);
				cir.setReturnValue(InteractionResult.FAIL);
				System.out.println("[sprucefarm-test] server refused a visible placement at " + placed);
			}
		}

		// Strict anti-cheat emulation: the placement succeeded, but the line
		// from the eye to the clicked point crosses a sign before the soil.
		// Roll the sapling back and report the click as failed, exactly the
		// "block above is now Air" rollback a strict server sends.
		if (TestSupport.strictSigns && stack.is(Items.SPRUCE_SAPLING)
			&& result != null && result.consumesAction()) {
			BlockPos placed = clicked.relative(hit.getDirection());
			if (level.getBlockState(placed).is(Blocks.SPRUCE_SAPLING)) {
				Vec3 eye = player.getEyePosition();
				BlockHitResult clip = level.clip(new ClipContext(eye, hit.getLocation(),
					ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
				BlockState first = clip.getType() == HitResult.Type.BLOCK
					? level.getBlockState(clip.getBlockPos()) : null;
				if (first != null && first.getBlock() instanceof SignBlock) {
					if (TestSupport.signAwareAntiCheat) {
						// Private-server anti-cheat setting: a sign board is a
						// non-solid decoration, so the line-of-sight check treats
						// it as transparent.  The click is still on the soil face
						// and the sign is not a wall the player can shoot through.
						System.out.println("[sprucefarm-test] sign-aware anti-cheat allowed a placement at "
							+ placed + " through sign " + clip.getBlockPos());
						return;
					}
					level.removeBlock(placed, false);
					cir.setReturnValue(InteractionResult.FAIL);
					System.out.println("[sprucefarm-test] strict server rolled back a covered placement at "
						+ placed + " (first block " + clip.getBlockPos() + " is a sign)");
				}
			}
		}
	}
}
