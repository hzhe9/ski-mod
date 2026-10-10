package com.hermes.skis.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * 脚部装备的自定义外观（Fabric ArmorRenderer）：模型与贴图在注册时传入，
 * 双板用 {@link SkiBoardModel}、单板用 {@link SnowboardModel}，都不再走原版护甲层贴图。
 */
public class BoardArmorRenderer implements net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer {
	private final Model<HumanoidRenderState> boardModel;
	private final Identifier texture;

	public BoardArmorRenderer(Model<HumanoidRenderState> boardModel, Identifier texture) {
		this.boardModel = boardModel;
		this.texture = texture;
	}

	@Override
	public void render(
			PoseStack poseStack,
			SubmitNodeCollector collector,
			ItemStack itemStack,
			HumanoidRenderState state,
			EquipmentSlot slot,
			int lightCoords,
			HumanoidModel<HumanoidRenderState> humanoidModel
	) {
		if (slot != EquipmentSlot.FEET) {
			return;
		}
		collector.order(0).submitModel(
				this.boardModel,
				state,
				poseStack,
				this.boardModel.renderType(this.texture),
				lightCoords,
				OverlayTexture.NO_OVERLAY,
				-1,
				null,
				state.outlineColor,
				null
		);
	}
}
