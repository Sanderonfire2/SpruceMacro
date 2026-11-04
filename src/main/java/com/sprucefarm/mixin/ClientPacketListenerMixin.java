package com.sprucefarm.mixin;

import com.sprucefarm.InventoryHelper;
import com.sprucefarm.ServerState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors authoritative server state into {@link ServerState} after the
 * vanilla handlers have applied it.  Reading the client level immediately
 * after a use would only see the local prediction; this is the state the
 * server actually sent back.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
	@Inject(method = "handleBlockUpdate", at = @At("TAIL"))
	private void sprucefarm$recordBlock(ClientboundBlockUpdatePacket packet, CallbackInfo ci) {
		ServerState.recordBlock(packet.getPos(), packet.getBlockState());
	}

	@Inject(method = "handleChunkBlocksUpdate", at = @At("TAIL"))
	private void sprucefarm$recordSectionBlocks(ClientboundSectionBlocksUpdatePacket packet, CallbackInfo ci) {
		packet.runUpdates((pos, state) -> ServerState.recordBlock(pos, state));
	}

	@Inject(method = "handleContainerContent", at = @At("TAIL"))
	private void sprucefarm$recordContents(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
		ServerState.recordContainerContent(packet.containerId());
		if (packet.containerId() == 0) {
			ServerState.recordContents(0, packet.items());
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		if (packet.containerId() != player.containerMenu.containerId) {
			// The client may have closed a chest the server still has open.  The
			// server keeps sending that chest's contents, including the player
			// inventory part; keeping it prevents a phantom stack from blocking
			// every later decision.
			ServerState.applyLateContent(packet.containerId(), packet.items());
			return;
		}
		AbstractContainerMenu menu = player.containerMenu;
		ServerState.rememberMenuLayout(packet.containerId(), menu, player);
		for (int slotIndex = 0; slotIndex < packet.items().size() && slotIndex < menu.slots.size(); slotIndex++) {
			recordIfPlayerSlot(player, menu, slotIndex, packet.items().get(slotIndex));
		}
	}

	@Inject(method = "handleContainerSetSlot", at = @At("TAIL"))
	private void sprucefarm$recordSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
		if (packet.getContainerId() == 0) {
			int inventoryIndex = InventoryHelper.inventoryIndexForMenuSlot(packet.getSlot());
			if (inventoryIndex >= 0) {
				ServerState.recordInventorySlot(inventoryIndex, packet.getItem());
			}
			return;
		}
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || packet.getContainerId() != player.containerMenu.containerId) {
			return;
		}
		recordIfPlayerSlot(player, player.containerMenu, packet.getSlot(), packet.getItem());
	}

	private static void recordIfPlayerSlot(LocalPlayer player, AbstractContainerMenu menu, int menuSlot, ItemStack stack) {
		if (menuSlot < 0 || menuSlot >= menu.slots.size()) {
			return;
		}
		Slot slot = menu.slots.get(menuSlot);
		if (slot.container == player.getInventory()) {
			ServerState.recordInventorySlot(slot.getContainerSlot(), stack);
		}
	}

	@Inject(method = "handleSetHeldSlot", at = @At("TAIL"))
	private void sprucefarm$recordHeldSlot(ClientboundSetHeldSlotPacket packet, CallbackInfo ci) {
		ServerState.recordHeldSlot(packet.slot());
	}
}
