package io.github.wf.skis.mixin.client;

import io.github.wf.skis.client.SkiStance;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.model.player.PlayerModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * <b>玩家腿切成两段：给每条腿加一个「大腿」子部件（枢轴在靴口）</b>。
 *
 * <p><b>为什么要切</b>：原版腿是一根 12 单位长的单节 box（髋 y=12 往下到 y=24），没有膝关节 ——
 * 站姿想屈膝就只能把整条腿绕髋倾，脚底（y=24）跟着往外甩、和雪鞋（{@code SkiBoardModel} /
 * {@code SnowboardModel} 的 boot，模型 y=18…24，正好是腿的下半段）穿模。
 * 把可见的腿裁到<b>上半段</b>（腿局部 y=0…6，即模型 y=12…18）、枢轴放到<b>靴口</b>（y=18），
 * 大腿就绕靴口翻，而雪鞋天然成了「竖直的下半段」—— 脚底、板面、鞋都不动，只有膝盖被顶出去。
 * 于是不需要另建小腿模型，也不会有第二套几何要维护。
 *
 * <p><b>为什么在 {@code createMesh} 的 RETURN 就地加子部件</b>：{@code PlayerModel.createMesh}
 * 是静态工厂，PLAYER 与 PLAYER_SLIM 两层都走它（{@code LayerDefinitions} 各调一次），
 * 所以在这里加孩子＝两种臂宽、所有玩家都同时拿到；{@code MeshDefinition} 是每个层一份的
 * 建模阶段数据（bake 之后才变成 {@code ModelPart}），不碰渲染期状态。
 *
 * <p><b>UV 为什么这么取（不是瞎凑）</b>：原版腿是 4×12×4 @ {@code texOffs(0,16)}（右腿）/
 * {@code texOffs(16,48)}（左腿）。MC 的盒子贴图里六个面占的区块是：两个端面在
 * {@code (u+d, v)} 与 {@code (u+d+w, v)}（都是 w×d），侧面在 {@code v+d … v+d+h}。
 * 换成 4×6×4 的同位盒（宽/深不变、只把高度从 12 砍到 6）：
 * <ul>
 *   <li>侧面落在 {@code v+4 … v+10}，即<b>原版腿侧面图的上 6 行</b> —— 正好是大腿那一段的皮肤；</li>
 *   <li>两个端面仍在 {@code (u+4, v)} / {@code (u+8, v)}：上端面（朝髋那一面）读的是原版腿顶盖的像素；
 *       下端面（朝靴口、局部 y=0 那一面）读到的正是原版腿的<b>鞋底</b>那块像素 ——
 *       但它埋在雪鞋顶面以下，永远看不见，所以不会露出错皮肤。</li>
 * </ul>
 * 外层裤腿同理：pants 是 4×12×4 + {@code CubeDeformation(0.25)}（右 {@code texOffs(0,32)}、
 * 左 {@code texOffs(0,48)}），取同样两个 UV，就得到「原版裤腿的上半段 + 半格外扩」，
 * 与原版腿、外层裤腿的相对位置完全一致。
 *
 * <p><b>为什么不直接改原版腿的 cube</b>：{@code MeshDefinition} 里 cube 的几何是烘死进
 * {@code ModelPart} 的，没有「只画上半段」的开关；能用的开关只有 {@code skipDraw}
 * （跳过<b>本部件自己的 cube</b>、仍然渲染 children），所以做法是：原版整条腿 {@code skipDraw = true}
 * 藏起来，我们的大腿挂成它的 child 照常画 —— 位姿仍由那条腿（髋枢轴）带着走，横向 / 前后位置不用重算。
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelThighMixin {
	// 本类只放 private 字段：Mixin 会把非 private 字段当成「要合并进目标类的成员」，非 private static
	// 字面量会被校验器直接拒掉（contains non-private static field）→ 资源重载失败、启动黑屏。
	// 所以大腿子部件的名字（跨类共享的常量）放在 SkiStance.THIGH_PART，不在这里。

	/** 靴口在<b>腿局部坐标</b>里的高度：髋往下 6 单位（模型 y = 12 + 6 = 18）＝ 雪鞋顶面。 */
	private static final float BOOT_TOP_Y = 6.0F;
	/** 大腿 cube 的高度：髋到靴口的 6 单位。 */
	private static final float THIGH_HEIGHT = 6.0F;
	/** 外层裤腿的扩展量，与原版 pants 一致（不写死 0.25 以外的东西，否则裤腿会比原版胖/瘦一圈）。 */
	private static final float PANTS_GROW = 0.25F;

	@Inject(method = "createMesh", at = @At("RETURN"))
	private static void skis$addThigh(CubeDeformation deformation, boolean slim,
			CallbackInfoReturnable<MeshDefinition> cir) {
		PartDefinition root = cir.getReturnValue().getRoot();
		// 左腿皮肤 16,48 / 左裤 0,48；右腿皮肤 0,16 / 右裤 0,32（与原版 createMesh 里的 texOffs 一一对应）
		addThigh(root.getChild("left_leg"), 16, 48, 0, 48);
		addThigh(root.getChild("right_leg"), 0, 16, 0, 32);
	}

	/**
	 * 给一条腿挂上大腿子部件：两个同位 cube（皮肤 + 外层裤腿），枢轴在靴口。
	 *
	 * @param leg 原版那条腿的 {@code PartDefinition}（模型 y=12、往下 12 单位）
	 */
	private static void addThigh(PartDefinition leg, int skinU, int skinV, int pantsU, int pantsV) {
		CubeListBuilder cubes = CubeListBuilder.create()
				.texOffs(skinU, skinV)
				.addBox(-2.0F, -THIGH_HEIGHT, -2.0F, 4.0F, THIGH_HEIGHT, 4.0F)
				.texOffs(pantsU, pantsV)
				.addBox(-2.0F, -THIGH_HEIGHT, -2.0F, 4.0F, THIGH_HEIGHT, 4.0F, new CubeDeformation(PANTS_GROW));
		// 局部 y=6 ＝ 靴口：往下 6 单位的 cube 正好补回髋到靴口那一段
		leg.addOrReplaceChild(SkiStance.THIGH_PART, cubes, PartPose.offset(0.0F, BOOT_TOP_Y, 0.0F));
	}
}
