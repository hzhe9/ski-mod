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
 * 脚部装备的自定义外观（Fabric ArmorRenderer）：
 * 装了 skis 模组的滑雪板就走这里，渲染自带的雪鞋 + 板身模型，不再用原版护甲层贴图。
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
