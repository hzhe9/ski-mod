package com.hermes.skis.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.hermes.skis.SkisMod;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * 脚部装备的自定义外观（Fabric ArmorRenderer）：双板用 {@link SkiBoardModel}、单板用 {@link SnowboardModel}。
 *
 * <p><b>6 种款式共用同一套模型，贴图按物品栈的 {@code minecraft:item_model} 组件切换</b>：
 * <pre>
 *   skis:item/modern_ski           -> textures/entity/ski_board.png            （基础款 wood）
 *   skis:item/modern_ski_graphite  -> textures/entity/ski_board_graphite.png   （其余 5 款同理）
 * </pre>
 * 没有组件（旧存档的板、创造栏直接取的默认物品）时回落到基础款贴图。
 */
public class BoardArmorRenderer implements net.fabricmc.fabric.api.client.rendering.v1.ArmorRenderer {
	private final Model<HumanoidRenderState> boardModel;
	/** 基础款贴图（wood）。 */
	private final Identifier baseTexture;
	/** 物品模型 id 前缀，用来取款式后缀："item/modern_ski" / "item/snowboard"。 */
	private final String itemModelPrefix;
	/** 脚上贴图前缀："ski_board" / "snowboard"。 */
	private final String textureBase;

	public BoardArmorRenderer(Model<HumanoidRenderState> boardModel, Identifier baseTexture,
			String itemModelPrefix, String textureBase) {
		this.boardModel = boardModel;
		this.baseTexture = baseTexture;
		this.itemModelPrefix = itemModelPrefix;
		this.textureBase = textureBase;
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
				this.boardModel.renderType(this.textureFor(itemStack)),
				lightCoords,
				OverlayTexture.NO_OVERLAY,
				-1,
				null,
				state.outlineColor,
				null
		);
	}

	/** 按款式组件选贴图：后缀为空 / 没组件 / 不是本模组的模型 → 基础款贴图。 */
	private Identifier textureFor(ItemStack stack) {
		Identifier model = stack.get(DataComponents.ITEM_MODEL);
		if (model == null
				|| !SkisMod.MOD_ID.equals(model.getNamespace())
				|| !model.getPath().startsWith(this.itemModelPrefix)) {
			return this.baseTexture;
		}
		String suffix = model.getPath().substring(this.itemModelPrefix.length());
		if (suffix.isEmpty()) {
			return this.baseTexture;
		}
		return Identifier.fromNamespaceAndPath(
				SkisMod.MOD_ID, "textures/entity/" + this.textureBase + suffix + ".png");
	}
}
