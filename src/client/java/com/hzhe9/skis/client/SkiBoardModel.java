package com.hzhe9.skis.client;

import com.hzhe9.skis.SkisMod;
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
 * 脚上真正看得见的滑雪装备：雪鞋（包住小腿下段）+ 板身 + 上翘板头。
 *
 * <p>坐标系沿用玩家模型（1 单位 = 1/16 格，脚底在 y=24，模型正前方是 -z），
 * 板面画在 y 23~24（贴着鞋底），板长 22 单位（约 1.37 格）向前伸 14 单位。
 *
 * <p>左右两组的横向位置取自 {@link SkiStance}——和 {@code HumanoidModelMixin} 平移双腿用的是同一个值，
 * 所以脚分开时板跟着一起分开，两块板保持平行、中间留间隔（不做任何外撇）。
 */
public class SkiBoardModel extends Model<HumanoidRenderState> {
	public static final ModelLayerLocation LAYER =
			new ModelLayerLocation(Identifier.fromNamespaceAndPath(SkisMod.MOD_ID, "ski_board"), "main");
	public static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath(SkisMod.MOD_ID, "textures/entity/ski_board.png");

	private static final float BOARD_TOP = 23.0F; // 板面（鞋底在 24）

	public SkiBoardModel(ModelPart root) {
		super(root, RenderTypes::entityCutout);
	}

	public static LayerDefinition createLayerDefinition() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		addSki(root, "right", -SkiStance.legOffsetX());
		addSki(root, "left", SkiStance.legOffsetX());
		return LayerDefinition.create(mesh, 128, 32);
	}

	private static void addSki(PartDefinition root, String side, float x) {
		PartDefinition ski = root.addOrReplaceChild("ski_" + side, CubeListBuilder.create(), PartPose.offset(x, 0.0F, 0.0F));
		// 雪鞋：包住小腿下段
		ski.addOrReplaceChild("boot_" + side,
				CubeListBuilder.create().texOffs(0, 0).addBox(-2.0F, 18.0F, -2.0F, 4.0F, 6.0F, 4.0F),
				PartPose.ZERO);
		// 板身：宽 3、厚 1、长 22；靴子在中点偏后（前 12.5 / 后 9.5 ≈ 前 57%，贴近现实安装位置）
		ski.addOrReplaceChild("board_" + side,
				CubeListBuilder.create().texOffs(20, 0).addBox(-1.5F, BOARD_TOP, -12.5F, 3.0F, 1.0F, 22.0F),
				PartPose.ZERO);
		// 板头上翘
		ski.addOrReplaceChild("tip_" + side,
				CubeListBuilder.create().texOffs(20, 24).addBox(-1.5F, 22.3F, -14.5F, 3.0F, 1.0F, 2.0F),
				PartPose.ZERO);
	}
}
