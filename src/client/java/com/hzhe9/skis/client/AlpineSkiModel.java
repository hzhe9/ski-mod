package com.hzhe9.skis.client;

import com.hzhe9.skis.AlpineSkiControl;
import com.hzhe9.skis.SkisMod;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
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
 *
 * <p><b>枢轴在髋（模型 y=12），不是 0</b>：转向时外侧腿要「整条（大腿 → 雪鞋 → 板）绕髋侧倒」，
 * 而雪鞋与板是<b>独立提交的模型</b>、不是玩家模型的子部件 —— 只有把 {@code ski_<side>} 这组的枢轴
 * 搬到髋，再给它写一个与腿<b>同角同向</b>的 {@code zRot}（{@link #setLegTilt}），三截才会共线。
 * 为此每块 cube 的 y 都减了 {@link #HIP_Y}（{@code 18 → 6}、{@code 23 → 11}、{@code 22.3 → 10.3}）——
 * <b>静止时（zRot = 0）世界位置逐字不变</b>，改的只是旋转中心。
 */
public class AlpineSkiModel extends BoardModel {
	public static final ModelLayerLocation LAYER =
			new ModelLayerLocation(Identifier.fromNamespaceAndPath(SkisMod.MOD_ID, "ski_board"), "main");
	public static final Identifier TEXTURE =
			Identifier.fromNamespaceAndPath(SkisMod.MOD_ID, "textures/entity/ski_board.png");

	/**
	 * 髋的高度（模型 y=12）：玩家模型的腿枢轴、也是这里 {@code ski_<side>} 组的枢轴 ——
	 * 两个枢轴必须在同一个点上，绕髋的侧倒才是同一个变换（见类注释）。
	 */
	private static final float HIP_Y = 12.0F;

	/** 右 / 左脚那一组（雪鞋 + 板身 + 翘头）；转向时给它们写 {@code zRot}（{@link #setLegTilt}）。 */
	private final ModelPart skiRight;
	private final ModelPart skiLeft;

	public AlpineSkiModel(ModelPart root) {
		super(root);
		this.skiRight = root.getChild("ski_right");
		this.skiLeft = root.getChild("ski_left");
	}

	public static LayerDefinition createLayerDefinition() {
		MeshDefinition mesh = new MeshDefinition();
		PartDefinition root = mesh.getRoot();
		addSki(root, "right", -SkiStance.legOffsetX());
		addSki(root, "left", SkiStance.legOffsetX());
		return layer(mesh);
	}

	/**
	 * <b>转向时外侧腿那一组（雪鞋 + 板）绕髋侧倒</b> —— 与玩家模型那块同一条腿的 {@code zRot}
	 * 同角同向（{@code AlpineSkiControl#legTiltRadians}，两边读同一份 {@code TURN_LEAN} 系数）。
	 *
	 * <p><b>为什么必须在 {@code setupAnim} 里写</b>：{@code ArmorRenderer#render} 里的
	 * {@code collector.submitModel(...)} 只是把「模型 + render state」**排进提交队列**，真正的
	 * {@code setupAnim(state)} → {@code renderToBuffer} 发生在之后刷队列时（26.2 的
	 * {@code ModelFeatureRenderer}）。在 render 里改部件再提交，刷队列时拿到的是**别的实体最后写下的**
	 * 姿势（多玩家同屏时全歪成同一个角），所以角必须由**这次的 state** 现算、在 setupAnim 里落到部件上。
	 *
	 * <p><b>单板一条都不碰</b>：{@code SnowboardModel} 有自己的 {@code setupAnim}（不覆盖），单板的整人倾斜
	 * 走 {@code EntityTiltMixin} 的 poseStack。
	 *
	 * <p>门与 {@code HumanoidModelMixin} 的腿段一致：不是 avatar（无 id）/ 游泳 / 鞘翅 / 骑乘 / 蹲行时
	 * 一律写 0（模型实例被所有渲染对象共用，必须两个方向都写到底）。
	 */
	@Override
	public void setupAnim(HumanoidRenderState state) {
		int entityId = state instanceof AvatarRenderState avatar ? avatar.id : -1;
		boolean stance = !state.isVisuallySwimming && !state.isFallFlying && !state.isPassenger;
		if (entityId < 0 || !stance || state.isCrouching) {
			setLegTilt(0.0F, 0.0F);
			return;
		}
		setLegTilt(
				AlpineSkiControl.legTiltRadians(entityId, true),
				AlpineSkiControl.legTiltRadians(entityId, false));
	}

	/**
	 * @param rightRadians 右腿侧倒角（弧度；正 ＝ 脚朝实体右侧倒，见 {@code AlpineSkiControl#legTiltRadians}）
	 * @param leftRadians  左腿侧倒角（弧度）
	 */
	public void setLegTilt(float rightRadians, float leftRadians) {
		this.skiRight.zRot = rightRadians;
		this.skiLeft.zRot = leftRadians;
	}

	private static void addSki(PartDefinition root, String side, float x) {
		// 枢轴搬到髋（y=12）：这一组于是能和腿绕同一个点侧倒（cube 的 y 统一减 HIP_Y，静止位置不变）
		PartDefinition ski = root.addOrReplaceChild("ski_" + side, CubeListBuilder.create(),
				PartPose.offset(x, HIP_Y, 0.0F));
		// 雪鞋：与 BoardModel#addBoot 同一个盒子（模型 y=18…24 ⇒ 组局部 6…12），
		// z 偏 0.05 单位同样是为了不与板身 / 腿的侧面共面（z-fighting），数值与那边一致
		ski.addOrReplaceChild("boot_" + side,
				CubeListBuilder.create().texOffs(0, 0).addBox(-2.0F, 18.0F - HIP_Y, -2.0F, 4.0F, 6.0F, 4.0F),
				PartPose.offset(0.0F, 0.0F, 0.05F));
		// 板身：宽 3、厚 1、长 24 单位（= 150 cm，成人全能板区间内）；靴子居中（前 12 / 后 12）
		ski.addOrReplaceChild("board_" + side,
				CubeListBuilder.create().texOffs(20, 0)
						.addBox(-1.5F, BOARD_TOP - HIP_Y, -12.0F, 3.0F, 1.0F, 24.0F),
				PartPose.ZERO);
		// 板头上翘（含翘头总长 26 单位 = 162.5 cm）
		ski.addOrReplaceChild("tip_" + side,
				CubeListBuilder.create().texOffs(20, 26)
						.addBox(-1.5F, 22.3F - HIP_Y, -14.0F, 3.0F, 1.0F, 2.0F),
				PartPose.ZERO);
	}
}
