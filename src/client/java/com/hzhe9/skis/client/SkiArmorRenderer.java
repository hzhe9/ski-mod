package com.hzhe9.skis.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * Custom appearance for the foot equipment (Fabric ArmorRenderer): a ski board from the skis mod is
 * rendered here with its own boot + board body model instead of the vanilla armor layer texture.
 */
public class SkiArmorRenderer implements net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer {
	private final SkiBoardModel skiModel;

	public SkiArmorRenderer(ModelPart root) {
		this.skiModel = new SkiBoardModel(root);
	}

	@Override
	public void render(
			PoseStack poseStack,
			SubmitNodeCollector collector,
			ItemStack itemStack,
			HumanoidRenderState state,
			EquipmentSlot slot,
			int lightCoords,
			HumanoidModel<HumanoidRenderState> model
	) {
		if (slot != EquipmentSlot.FEET) {
			return;
		}
		collector.order(0).submitModel(
				this.skiModel,
				state,
				poseStack,
				this.skiModel.renderType(SkiBoardModel.TEXTURE),
				lightCoords,
				OverlayTexture.NO_OVERLAY,
				-1,
				null,
				state.outlineColor,
				null
		);
	}
}
