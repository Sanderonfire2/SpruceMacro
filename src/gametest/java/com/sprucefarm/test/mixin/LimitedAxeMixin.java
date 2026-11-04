package com.sprucefarm.test.mixin;

import com.sprucefarm.test.TestSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Test-only stand-in for the server's Shard Axe.  Breaking a spruce log with
 * an axe in hand schedules the connected logs above it for removal, optionally
 * with a delay and one log per N ticks.  That lets the client's harvest
 * verification be tested against a plugin that fells gradually instead of
 * clearing a whole tree in the same tick.
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class LimitedAxeMixin {
	@Shadow
	protected ServerLevel level;

	@Shadow
	protected ServerPlayer player;

	private BlockState sprucefarm$brokenState;

	@Inject(method = "destroyBlock", at = @At("HEAD"))
	private void sprucefarm$captureBrokenState(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		this.sprucefarm$brokenState = this.level.getBlockState(pos);
	}

	@Inject(method = "destroyBlock", at = @At("RETURN"))
	private void sprucefarm$fellLikeShardAxe(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		BlockState state = this.sprucefarm$brokenState;
		this.sprucefarm$brokenState = null;
		if (!TestSupport.limitedAxe || !cir.getReturnValue() || state == null
			|| !state.is(Blocks.SPRUCE_LOG)) {
			return;
		}
		if (!(this.player.getMainHandItem().getItem() instanceof AxeItem)) {
			return;
		}
		TestSupport.startFell(this.level, pos);
	}
}
