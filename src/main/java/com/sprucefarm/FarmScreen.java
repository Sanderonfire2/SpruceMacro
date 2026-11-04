package com.sprucefarm;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public final class FarmScreen extends Screen {
	private static final int[] DELAYS = {4, 6, 8, 10, 14, 20};

	private final FarmController controller;
	private Button startButton;
	private Button pauseButton;
	private Button stopButton;
	private Button rescanButton;
	private Button axeButton;
	private Button delayButton;
	private String notice = "";

	public FarmScreen(FarmController controller) {
		super(Component.literal("Spruce Farm Automator " + version()));
		this.controller = controller;
	}

	/** Shown in the panel so a support log tells which build is running. */
	private static String version() {
		return net.fabricmc.loader.api.FabricLoader.getInstance()
			.getModContainer(SpruceFarmClient.MOD_ID)
			.map(container -> "v" + container.getMetadata().getVersion().getFriendlyString())
			.orElse("");
	}

	@Override
	protected void init() {
		super.init();
		int centre = this.width / 2;
		int top = Math.max(0, (this.height - 238) / 2);

		startButton = this.addRenderableWidget(Button.builder(
			Component.literal("Start"),
			button -> {
				notice = "";
				controller.start();
			}
		).bounds(centre - 145, top + 190, 90, 20).build());

		pauseButton = this.addRenderableWidget(Button.builder(
			Component.literal("Pause"),
			button -> controller.togglePause()
		).bounds(centre - 45, top + 190, 90, 20).build());

		stopButton = this.addRenderableWidget(Button.builder(
			Component.literal("Stop"),
			button -> controller.stop()
		).bounds(centre + 55, top + 190, 90, 20).build());

		rescanButton = this.addRenderableWidget(Button.builder(
			Component.literal("Rescan"),
			button -> {
				controller.rescan();
				notice = "Layout rescanned";
			}
		).bounds(centre - 150, top + 216, 90, 20).build());

		axeButton = this.addRenderableWidget(Button.builder(
			Component.literal("Set axe"),
			button -> {
				if (controller.client().player == null) {
					return;
				}
				ItemStack held = controller.client().player.getMainHandItem();
				if (held.isEmpty()) {
					notice = "Hold the special axe first.";
				} else {
					controller.config().axeOverride = held.getItem();
					notice = "Axe set: " + held.getHoverName().getString();
				}
			}
		).bounds(centre - 55, top + 216, 100, 20).build());

		delayButton = this.addRenderableWidget(Button.builder(
			Component.literal("Speed"),
			button -> {
				FarmConfig config = controller.config();
				if (config.fastMode) {
					config.fastMode = false;
				} else {
					int current = config.actionDelayTicks;
					int next = DELAYS[0];
					for (int i = 0; i < DELAYS.length; i++) {
						if (DELAYS[i] == current) {
							next = DELAYS[(i + 1) % DELAYS.length];
							break;
						}
					}
					if (next == DELAYS[0]) {
						config.fastMode = true;
					} else {
						config.actionDelayTicks = next;
					}
				}
				config.save();
			}
		).bounds(centre + 50, top + 216, 110, 20).build());
	}

	@Override
	public void tick() {
		super.tick();
		pauseButton.setMessage(Component.literal(controller.paused() ? "Resume" : "Pause"));
		pauseButton.active = controller.isActive();
		stopButton.active = controller.isActive() || controller.paused();
		startButton.active = !controller.isActive();
		delayButton.setMessage(Component.literal(controller.config().fastMode
			? "Speed: FAST"
			: "Speed: " + (controller.config().actionDelayTicks * 50) + " ms"));
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		int panelWidth = Math.min(426, this.width - 10);
		int panelHeight = 238;
		int left = (this.width - panelWidth) / 2;
		int top = Math.max(0, (this.height - panelHeight) / 2);
		int textX = left + 16;
		int rightX = left + panelWidth / 2 + 8;

		graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xE0101010);
		graphics.fill(left, top, left + panelWidth, top + 1, 0xFF3A7A3A);
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, top + 10, 0xFF9BE59B);

		FarmStats cycle = controller.cycleStats();
		FarmStats total = controller.totalStats();

		graphics.drawString(this.font, "Stage: " + controller.stageLabel(), textX, top + 30, 0xFFFFFFFF);
		graphics.drawString(this.font, "Cycle: " + controller.cycle(), rightX, top + 30, 0xFFAAAAAA);
		drawWrapped(graphics, "Status: " + controller.status(), textX, top + 44, 0xFFCCCCCC, panelWidth - 32, 2);

		graphics.drawString(this.font,
			"Planted: " + cycle.planted + "  (total " + total.planted + ")",
			textX, top + 70, 0xFF9BE59B);
		graphics.drawString(this.font,
			"Grown: " + cycle.grown + "  (total " + total.grown + ")",
			textX, top + 82, 0xFF9BE59B);
		graphics.drawString(this.font,
			"Harvested: " + cycle.harvested + "  (total " + total.harvested + ")",
			textX, top + 94, 0xFF9BE59B);

		var player = controller.client().player;
		int bonemeal = player == null ? 0 : InventoryHelper.countBonemeal(player);
		int freeSlots = player == null ? 0 : InventoryHelper.freeSlots(player);
		graphics.drawString(this.font, fit("Bonemeal: " + bonemeal
			+ "   Free slots: " + freeSlots + " (sell at " + controller.config().sellFreeSlots + ")"
			+ "   Extra fells: " + total.extraFells + "   Skipped: " + total.skippedTrees,
			panelWidth - 32), textX, top + 112, 0xFFFFFFFF);

		graphics.drawString(this.font, fit("Chests: " + controller.chestSummary(), panelWidth - 32),
			textX, top + 124, 0xFFAAAAAA);

		int logs = player == null ? 0 : InventoryHelper.countLogs(player);
		int sticks = player == null ? 0 : InventoryHelper.countSticks(player);
		int saplings = player == null ? 0 : InventoryHelper.countSaplings(player);
		graphics.drawString(this.font,
			"Logs: " + logs + "   Sticks: " + sticks + "   Saplings: " + saplings + " (keep "
				+ controller.config().keepSaplings + ")",
			textX, top + 138, 0xFFAAAAAA);

		graphics.drawString(this.font,
			"Pads: " + controller.pads() + "   Spots: " + controller.spots().size() + "/"
				+ controller.config().expectedSpots + "   Blocked: " + controller.blockedSpots()
				+ "   Out of reach: " + controller.unreachableSpots(),
			textX, top + 150, 0xFFAAAAAA);

		if (!controller.error().isEmpty()) {
			drawWrapped(graphics, "Error: " + controller.error(), textX, top + 164, 0xFFFF6B6B, panelWidth - 32, 2);
		} else if (!controller.warning().isEmpty()) {
			drawWrapped(graphics, controller.warning(), textX, top + 164, 0xFFFFAA00, panelWidth - 32, 2);
		} else if (!notice.isEmpty()) {
			graphics.drawString(this.font, notice, textX, top + 164, 0xFF9BE59B);
		}

		if (controller.error().isEmpty() && controller.warning().isEmpty()) {
			graphics.drawString(this.font, "Player must remain still. Key O opens this panel.",
				textX, top + 178, 0xFF777777);
		}
	}

	private void drawWrapped(GuiGraphics graphics, String text, int x, int y, int colour, int maxWidth, int maxLines) {
		List<FormattedCharSequence> lines = this.font.split(Component.literal(text), maxWidth);
		int drawn = 0;
		for (FormattedCharSequence line : lines) {
			if (drawn >= maxLines) {
				break;
			}
			graphics.drawString(this.font, line, x, y + drawn * 10, colour);
			drawn++;
		}
	}

	private String fit(String text, int maxWidth) {
		if (this.font.width(text) <= maxWidth) {
			return text;
		}
		String trimmed = text;
		while (trimmed.length() > 3 && this.font.width(trimmed + "...") > maxWidth) {
			trimmed = trimmed.substring(0, trimmed.length() - 1);
		}
		return trimmed + "...";
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
