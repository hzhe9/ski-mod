package io.github.wf.skis.client;

import io.github.wf.skis.SkisMod;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.Identifier;

/**
 * <b>双板专属</b>的外形：脚上真正看得见的滑雪装备 —— 雪鞋（包住小腿下段）+ 板身 + 上翘板头。
 *
 * <p>只服务双板那一把（{@code SkisMod.MODERN_SKI}）；单板是 {@link SnowboardModel}。
 * 两块板逐字相同的骨架（板面高度、雪鞋盒子、图层尺寸、渲染类型）在 {@link BoardModel}。
 *
 * <p>坐标系沿用玩家模型（1 单位 = 1/16 格，脚底在 y=24，模型正前方是 -z），
 * 板面画在 y 23~24（贴着鞋底），板长 22 单位（约 1.37 格）向前伸 14 单位。
 *
 * <p>左右两组的横向位置取自 {@link SkiStance}——和 {@code HumanoidModelMixin} 平移双腿用的是同一个值，
 * 所以脚分开时板跟着一起分开，两块板保持平行、中间留间隔（不做任何外撇）。
 */
public class AlpineSkiModel extends BoardModel {
	public static final ModelLayerLocation LAYER =
			new ModelLayerLocation(Identifier.fromNamespaceAndPath(SkisMod.MOD_ID, "ski_board"), "main");
	public static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath(SkisMod.MOD_ID, "textures/entity/ski_board.png");

	public AlpineSkiModel(ModelPart root) {
		super(root);
	}

	public static LayerDefinition createLayerDefinition() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		addSki(root, "right", -SkiStance.legOffsetX());
		addSki(root, "left", SkiStance.legOffsetX());
		return layer(mesh);
	}

	private static void addSki(PartDefinition root, String side, float x) {
		PartDefinition ski = root.addOrReplaceChild("ski_" + side, CubeListBuilder.create(), PartPose.offset(x, 0.0F, 0.0F));
		// 雪鞋：包住小腿下段（盒子尺寸与 z 偏移见 BoardModel#addBoot）
		addBoot(ski, "boot_" + side, 0.0F);
		// 板身：宽 3、厚 1、长 24 单位（= 150 cm，成人全能板区间内）；靴子居中（前 12 / 后 12）
		ski.addOrReplaceChild("board_" + side,
				CubeListBuilder.create().texOffs(20, 0).addBox(-1.5F, BOARD_TOP, -12.0F, 3.0F, 1.0F, 24.0F),
				PartPose.ZERO);
		// 板头上翘（含翘头总长 26 单位 = 162.5 cm）
		ski.addOrReplaceChild("tip_" + side,
				CubeListBuilder.create().texOffs(20, 26).addBox(-1.5F, 22.3F, -14.0F, 3.0F, 1.0F, 2.0F),
				PartPose.ZERO);
	}
}
