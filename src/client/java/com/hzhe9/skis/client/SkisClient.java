package com.hzhe9.skis.client;

import com.hzhe9.skis.SkisMod;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import net.minecraft.world.entity.player.Player;

public class SkisClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ModelLayerRegistry.registerModelLayer(SkiBoardModel.LAYER, SkiBoardModel::createLayerDefinition);
		ArmorRenderer.register(
				context -> new SkiArmorRenderer(context.bakeLayer(SkiBoardModel.LAYER)),
				SkisMod.MODERN_SKI
		);

		// Record "on ground" from the real entity every tick — the render state is rebuilt every
		// frame, so it must not be relied on for the airborne check
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.level == null) {
				return;
			}
			for (Player player : client.level.players()) {
				SkiState.setOnGround(player.getId(), player.onGround());
			}
			SkiState.prune();
		});
	}
}
