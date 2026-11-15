package io.github.wf.skis.client;

import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * 两块板模型（双板 {@link AlpineSkiModel}、单板 {@link SnowboardModel}）共用的骨架 ——
 * 只放两边逐字相同的部分，板身 / 板头 / 板的朝向仍各在各的类里。
 *
 * <p>共用项：
 * <ul>
 *   <li>{@link #BOARD_TOP}：板面（＝板顶面）在模型空间里的 y 值，鞋底在 24；</li>
 *   <li>雪鞋盒子 {@code addBox(-2, 18, -2, 4, 6, 4)}＋{@code texOffs(0, 0)}＋{@code PartPose.offset(x, 0, 0.05)}
 *       —— 与腿的下半段同形同位，2×3×2 像素的小腿段；</li>
 *   <li>图层尺寸 128×32 与渲染类型 {@code entityCutout}（两块板共用一张贴图布局）。</li>
 * </ul>
 */
abstract class BoardModel extends Model<HumanoidRenderState> {
	/** 板身与翘头在模型空间里的 y 值（板顶面高度；鞋底在 24）。 */
	static final float BOARD_TOP = 23.0F;

	/**
	 * @param root 已烘焙的模型根节点
	 */
	BoardModel(ModelPart root) {
		// 两块板同一套渲染类型：带 alpha 剪裁的实体贴图（板身有镂空 / 边缘）
		super(root, RenderTypes::entityCutout);
	}

	/**
	 * 加一只雪鞋：{@code -2 … 2} 见方、{@code y 18 … 24}（正好顶到鞋底），贴图 {@code texOffs(0, 0)}。
	 *
	 * @param parent 挂到哪个节点下（双板挂在 {@code ski_<side>} 组里、单板挂在模型根节点下）
	 * @param name   子部件名（两块板都叫 {@code boot_<side>}）
	 * @param x      横向位置（模型单位；双板为 0，单板为 ±{@link SkiStance#BOARD_LEG_SPREAD}）
	 */
	static void addBoot(PartDefinition parent, String name, float x) {
		// z 偏 0.05 单位（约 0.3 cm）：让鞋的侧面**不与板身 / 腿的侧面共面** ——
		// 共面会让两面 z-fighting（渲染出一片锯齿点，游戏里同样会闪）。0.05 肉眼看不出来。
		parent.addOrReplaceChild(name,
				CubeListBuilder.create().texOffs(0, 0).addBox(-2.0F, 18.0F, -2.0F, 4.0F, 6.0F, 4.0F),
				PartPose.offset(x, 0.0F, 0.05F));
	}

	/**
	 * 收尾：按两块板共用的贴图布局（128 × 32 像素）建图层定义。
	 *
	 * @param mesh 已经加好全部部件的 {@link MeshDefinition}
	 */
	static LayerDefinition layer(MeshDefinition mesh) {
		return LayerDefinition.create(mesh, 128, 32);
	}
}
