package com.sprucefarm;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SpruceFarmClient implements ClientModInitializer {
	public static final String MOD_ID = "sprucefarm";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static FarmConfig config;
	private static FarmController controller;
	private static KeyMapping openKey;

	@Override
	public void onInitializeClient() {
		config = FarmConfig.load();
		controller = new FarmController(Minecraft.getInstance(), config);
		openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
			"key.sprucefarm.open",
			InputConstants.Type.KEYSYM,
			GLFW.GLFW_KEY_O,
			KeyMapping.Category.MISC
		));

		// Vanilla handles a right-click in Minecraft.tick() before the local
		// player's movement packet, and only then sends ServerboundMovePlayerPacket
		// and ServerboundClientTickEndPacket.  START_CLIENT_TICK matches that
		// pre-movement ordering.  END_WORLD_TICK is after the movement packet,
		// which strict anti-cheats label a post-motion placement and cancel
		// (the sapling reappears as a ghost block).  END_CLIENT_TICK is even
		// later and caused the original Invalid sequence kick.
		ClientTickEvents.START_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				if (client.screen == null && client.player != null) {
					client.setScreen(new FarmScreen(controller));
				}
			}
			controller.tick();
		});

		// Server-confirmed block and inventory state must never leak across
		// worlds: a stale sapling slot from the previous server would make the
		// mod believe it is holding saplings when the new server says empty.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> ServerState.clear());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ServerState.clear());

		LOGGER.info("Spruce Farm Automator ready (Minecraft 1.21.11, key O)");
	}

	public static FarmController controller() {
		return controller;
	}

	public static FarmConfig config() {
		return config;
	}
}
