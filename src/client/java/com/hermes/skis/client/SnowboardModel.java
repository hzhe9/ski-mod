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
 * 脚上看得见的滑雪单板：一只板（宽 9、厚 1、长 24；前伸 14 / 后拖 10）+ 上翘板头 + 两只雪鞋。
 *
 * <p>两只鞋横跨在板上、按鸭子站姿反向外撇，前后位置与偏转角取自 {@link SkiStance}——
 * 和 {@code HumanoidModelMixin} 摆腿用的是同一组常量，所以腿和鞋永远对得上。
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

		root.addOrReplaceChild("board",
				CubeListBuilder.create().texOffs(20, 0).addBox(-4.5F, BOARD_TOP, -14.0F, 9.0F, 1.0F, 24.0F),
				PartPose.ZERO);
		root.addOrReplaceChild("tip",
				CubeListBuilder.create().texOffs(90, 0).addBox(-4.5F, 22.3F, -16.0F, 9.0F, 1.0F, 2.0F),
				PartPose.ZERO);

		// 左脚在前（regular 站姿）：前脚向前错开并朝外撇，后脚反向
		addBoot(root, "left", SkiStance.BASE_LEG_X, SkiStance.BOARD_FRONT_LEG_Z, SkiStance.BOARD_STANCE_RAD);
		addBoot(root, "right", -SkiStance.BASE_LEG_X, SkiStance.BOARD_BACK_LEG_Z, -SkiStance.BOARD_STANCE_RAD);
		return LayerDefinition.create(mesh, 128, 32);
	}

	private static void addBoot(PartDefinition root, String side, float x, float z, float yRot) {
		root.addOrReplaceChild("boot_" + side,
				CubeListBuilder.create().texOffs(0, 0).addBox(-2.0F, 18.0F, -2.0F, 4.0F, 6.0F, 4.0F),
				PartPose.offsetAndRotation(x, 0.0F, z, 0.0F, yRot, 0.0F));
	}
}
