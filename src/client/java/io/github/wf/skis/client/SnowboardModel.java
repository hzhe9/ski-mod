package io.github.wf.skis.client;

import io.github.wf.skis.SkisMod;
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

		// 板身（板上坐标：宽沿 x、长沿 z）—— 宽 5.76 单位 = 36 cm（一条单板的真实板宽量级），前后各 11
		board.addOrReplaceChild("deck",
				CubeListBuilder.create().texOffs(20, 0).addBox(-2.88F, BOARD_TOP, -11.0F, 5.76F, 1.0F, 22.0F),
				PartPose.ZERO);
		// 上翘板头（-z 端）
		board.addOrReplaceChild("tip",
				CubeListBuilder.create().texOffs(90, 0).addBox(-2.88F, 22.3F, -13.0F, 5.76F, 1.0F, 2.0F),
				PartPose.ZERO);
		// 上翘板尾（+z 端）—— 与板头镜像，做成 twin-tip：两头一样翘、前后左右都对称
		board.addOrReplaceChild("tail",
				CubeListBuilder.create().texOffs(90, 4).addBox(-2.88F, 22.3F, 11.0F, 5.76F, 1.0F, 2.0F),
				PartPose.ZERO);
		// 两只鞋：建在根节点下、平行并列向外岔开（与腿同一坐标系、同一横向偏移）
		addBoot(root, "left", SkiStance.BOARD_LEG_SPREAD);
		addBoot(root, "right", -SkiStance.BOARD_LEG_SPREAD);
		return LayerDefinition.create(mesh, 128, 32);
	}

	private static void addBoot(PartDefinition root, String side, float x) {
		// z 偏 0.05 单位（0.3 cm）：让靴子的侧面**不与板身的长边共面**，也不与腿的侧面共面 ——
		// 共面会让两面 z-fighting（渲染出一片锯齿点，游戏里同样会闪）。0.05 肉眼看不出来。
		root.addOrReplaceChild("boot_" + side,
				CubeListBuilder.create().texOffs(0, 0).addBox(-2.0F, 18.0F, -2.0F, 4.0F, 6.0F, 4.0F),
				PartPose.offset(x, 0.0F, 0.05F));
	}
}
