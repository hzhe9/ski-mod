package com.hermes.skis.client;

import com.hermes.skis.SkisMod;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.resources.Identifier;

/**
 * 脚上看得见的滑雪单板。
 *
 * <p><b>整组绕 Y 轴转 90°</b>（{@link SkiStance#BOARD_YAW_RAD}）：默认建模朝向是朝前（-z，与玩家朝向一致），
 * 而单板是横在脚下、与（第一人称）视线方向垂直的 —— 转完之后板身的长边落在 x 轴上，即“横着”。
 *
 * <p>组内几何按“板上坐标”建（板长沿 z、宽沿 x）。两只<b>雪鞋不放在这个板组里</b>，而是直接建在模型根节点下、
 * 用与腿同一套模型坐标（{@link SkiStance#BOARD_LEG_SPREAD}）—— 放进转了 90° 的组里会让鞋与腿朝向差 90°，
 * 看起来就是“脚歪了”。
 *
 * <p>几何：板身宽 9、厚 1、长 24（前伸 14 / 后拖 10），板面贴在 y=23（鞋底在 24），板头在 -z 端上翘。
 */
public class SnowboardModel extends Model<HumanoidRenderState> {
	public static final ModelLayerLocation LAYER =
			new ModelLayerLocation(Identifier.fromNamespaceAndPath(SkisMod.MOD_ID, "snowboard"), "main");
	public static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath(SkisMod.MOD_ID, "textures/entity/snowboard.png");

	private static final float BOARD_TOP = 23.0F; // 板面（鞋底在 24）

	public SnowboardModel(ModelPart root) {
		super(root, RenderTypes::entityCutout);
	}

	public static LayerDefinition createLayerDefinition() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();

		// 整组转 90°：横在脚下
		PartDefinition board = root.addOrReplaceChild("snowboard",
				CubeListBuilder.create(), PartPose.rotation(0.0F, SkiStance.BOARD_YAW_RAD, 0.0F));

		// 板身（板上坐标：宽沿 x、长沿 z）—— 前后各 12，人物正好站在板的几何正中
		board.addOrReplaceChild("deck",
				CubeListBuilder.create().texOffs(20, 0).addBox(-4.5F, BOARD_TOP, -12.0F, 9.0F, 1.0F, 24.0F),
				PartPose.ZERO);
		// 上翘板头（-z 端 = 板的板头），接在板身前缘之外
		board.addOrReplaceChild("tip",
				CubeListBuilder.create().texOffs(90, 0).addBox(-4.5F, 22.3F, -14.0F, 9.0F, 1.0F, 2.0F),
				PartPose.ZERO);
		// 两只鞋：建在根节点下、平行并列向外岔开（与腿同一坐标系、同一横向偏移）
		addBoot(root, "left", SkiStance.BOARD_LEG_SPREAD);
		addBoot(root, "right", -SkiStance.BOARD_LEG_SPREAD);
		return LayerDefinition.create(mesh, 128, 32);
	}

	private static void addBoot(PartDefinition root, String side, float x) {
		root.addOrReplaceChild("boot_" + side,
				CubeListBuilder.create().texOffs(0, 0).addBox(-2.0F, 18.0F, -2.0F, 4.0F, 6.0F, 4.0F),
				PartPose.offset(x, 0.0F, 0.0F));
	}
}
