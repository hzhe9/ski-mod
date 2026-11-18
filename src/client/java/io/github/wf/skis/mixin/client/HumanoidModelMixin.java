package io.github.wf.skis.mixin.client;

import io.github.wf.skis.SkiAir;
import io.github.wf.skis.SkisMod;
import io.github.wf.skis.client.SkiStance;
import io.github.wf.skis.client.SkiState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.util.Mth;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 变体版（右键冲刺）姿态：
 *
 * <ul>
 *   <li>双腿：取消走路摆腿、不做外撇，只把左右腿各向外平移 {@link SkiStance#LEG_SPREAD}
 *       （脚上的板模型用同一个量，跟着分开、平行）；同时<b>屈膝微蹲</b> ——
 *       <b>大腿</b>绕靴口翻 {@link SkiStance#SQUAT_LEG_TILT}、躯干前压 {@link SkiStance#SQUAT_BODY_LEAN}、
 *       头略低 {@link SkiStance#SQUAT_HEAD_PITCH}。<b>跳跃</b>那一趟（{@code jumpedThisAir}）腿与躯干收到
 *       {@link SkiStance#SQUAT_LEG_TILT_AIR} -0.19 / {@link SkiStance#SQUAT_BODY_LEAN_AIR} 0.12 ——
 *       比地面值只缩一点点；按 {@link SkiAir#airPoseBlend}(id) 混合，blend <b>只有 0 / 1</b>
 *       （判据只有「这一趟是不是跳的」，不再看离地高度、也没有渐变）。<b>非跳跃的腾空</b>（坡上滑落 /
 *       走下台阶 / 掉下去）保持<b>地面姿势</b>；落地那一 tick 立刻回地面值。</li>
 *   <li>双臂：<b>静止</b>固定在划动的中间位置，不随移动摆动；</li>
 *   <li>右键撑杖时播放<b>一次</b>摆臂（前摆再收回，约 0.6 秒），由 {@link SkiState} 的倒计时驱动；</li>
 *   <li>身体：轻微前倾。</li>
 * </ul>
 *
 * <p><b>摆臂只对本机玩家生效</b>（已知限制）：{@link SkiState} 那张表只有本机玩家的 id 会被写入
 * （触发点是本机右键 {@code SwingHooks}，没有任何 S2C 包），所以<b>远端玩家在你屏幕上永远不摆臂</b>，
 * 你在别人屏幕上也一样。模型层因此只对「本机 avatar」读这张表（{@link #isLocalAvatar}）——
 * 对所有人读一张只有本机有数据的表，等于给每个远端 avatar 做一次注定返回 0 的查表，
 * 还会让后来的人误以为「只要发包就能看到别人摆臂」。要修只能是新增一个 S2C/SwingPayload（本轮明确不做）。
 *
 * <p><b>腿为什么是「两段」</b>：原版腿只有一根 12 单位的单节 box（髋 y=12 → 脚底 y=24），没有膝关节，
 * 绕髋倾就会把脚底甩出去、和雪鞋穿模。所以 {@code PlayerModelThighMixin} 给每条腿加了一个
 * <b>大腿子部件</b>（枢轴在靴口，模型 y=18，往下 6 单位补回髋到靴口那一段），这里把原版整条腿
 * {@code skipDraw = true} 藏掉、只让那个大腿子部件绕靴口翻（{@code xRot = SQUAT_LEG_TILT}，
 * 负角＝膝往前顶）。雪鞋（{@code AlpineSkiModel} / {@code SnowboardModel} 的 boot，模型 y=18…24，
 * 与腿下半段同形同位）由此成为<b>竖直的下半段</b>：脚底、板面、鞋都不动，只有膝盖被顶出去。
 * 腿本身仍是原版的位置（{@code y} 恒 12、{@code yScale} 恒 1.0，一律不写），所以脚底与板面始终对齐。
 *
 * <p>游泳 / 鞘翅 / 骑乘时保持原版姿势。
 */
@Mixin(HumanoidModel.class)
public abstract class HumanoidModelMixin {
	/** 腿部枢轴的原始高度。 */
	private static final float LEG_PIVOT_Y = 12.0F;
	/** 划动中心角度（最高处与最低处由它加减幅度得到）。 */
	private static final float ROW_CENTER = -0.35F;
	/** 划动幅度：最高处 = ROW_CENTER - 幅度（前摆到底），最低处 = ROW_CENTER + 幅度（后摆到底）。 */
	private static final float ROW_AMPLITUDE = 0.95F;
	/** 最高处（举起到底）。 */
	private static final float ARM_TOP = ROW_CENTER - ROW_AMPLITUDE;
	/** 静止时手杖停的位置：不要举到最高，往下放一点。 */
	private static final float ARM_REST = -0.55F;
	/** 右键动画里“先举到最高”这一段占的比例，剩下的时间才往下摆、再收回。 */
	private static final float RAISE_FRACTION = 0.25F;   // 举起段占比（25%）：抬手只占前四分之一，余下留给下摆
	/** 最低处：一次撑杖摆到的地方。 */
	private static final float ARM_BOTTOM = ROW_CENTER + ROW_AMPLITUDE;
	/** 下摆占整段动画的比例，剩下的时间收回最高处。 */
	private static final float DOWN_FRACTION = 0.6F;
	/** 手臂向外的滚转角（正负对称，保证两根滑雪杖平行、不向内倾斜）。 */
	private static final float ARM_OUTWARD_ROLL = 0.10F;
	/** 单板滑行时两只手的前后角（弧度）：0 = 垂在身侧，负值 = 向前抬。不要前抬，保持 0。 */
	private static final float FREE_ARM_XROT = 0.0F;
	/**
	 * 单板滑行时两只手**向两侧微微抬起**的角度（绕前后轴，弧度）——
	 * 右臂取 +、左臂取 -，于是两只手对称地离开身体侧面。0.28 rad ≈ 16°，微微抬起。
	 */
	private static final float FREE_ARM_ROLL = 0.28F;
	/**
	 * <b>双板</b>空手（没拿滑雪杖）时两臂**向前微微抬起**的角度（弧度）——
	 * 负值 = 向前抬（与 {@link #ARM_TOP} 同一个方向约定）。0.25 rad ≈ 14°。
	 */
	private static final float SKI_FREE_ARM_XROT = -0.25F;
	/** 双板空手时两臂**向两侧微微抬起**的角度（弧度，右臂 + / 左臂 -）。 */
	private static final float SKI_FREE_ARM_ROLL = 0.28F;

	/**
	 * 上一帧是不是<b>我们自己</b>把这条腿（护甲模型那种没有大腿子部件的）藏起来的。
	 *
	 * <p>用一个实例字段记住，是为了不覆盖原版自己的可见性逻辑：只有「我们藏过」时才由我们恢复；
	 * 原版本就把腿藏起来时（某些模型本来不画腿）我们不去动它。
	 */
	private boolean skis$armorLegsHidden;

	/**
	 * 「玩家模型上找不到大腿子部件」这条 warn 只打一次。
	 *
	 * <p>{@code @Unique}：这个 mixin 的字段会被并进 {@code HumanoidModel}，加注解免得与目标类的同名成员撞。
	 */
	@Unique
	private boolean skis$warnedMissingThigh;

	@Inject(method = "setupAnim", at = @At("TAIL"))
	private void skis$applyGlideStance(HumanoidRenderState state, CallbackInfo ci) {
		HumanoidModel<?> self = (HumanoidModel<?>) (Object) this;

		boolean stance = SkisMod.isSkiFeet(state.feetEquipment)
				&& !state.isVisuallySwimming && !state.isFallFlying && !state.isPassenger;
		// 空中站姿的混合系数（0 = 地面、1 = 空中）：只查客户端那张表，一次 map 查询、null 安全。
		// 只有两个值：这一趟腾空里起过跳（jumpedThisAir）＝ 1（缩一点点），其余一切 ＝ 0（地面姿势）。
		// 不再看离地高度、也没有渐变（那条路已删，见 SkiAir.airPoseBlend 的 javadoc）。
		// 非 Avatar（僵尸 / 护甲模型等）与远端玩家都没有状态 → 0（＝按地面姿势渲染，已知限制同上）。
		int entityId = state instanceof AvatarRenderState avatar ? avatar.id : -1;
		float airBlend = entityId >= 0 ? SkiAir.airPoseBlend(entityId) : 0.0F;
		// 腿的开关（skipDraw / 大腿 visible / 护甲腿 visible）**每一帧两个方向都写到底**：
		// 同一个模型实例被所有渲染对象共用（玩家、不穿板的人），漏写一条分支就会把上一个
		// 渲染对象留下的状态带给下一个 —— 那正是「不穿板的人腿少了一截」的来源。
		skis$poseLegSplit(self, stance, airBlend);
		if (!stance) {
			return;
		}

		boolean board = state.feetEquipment.getItem() == SkisMod.SNOWBOARD;
		if (board) {
			// 单板：与双板同一套站姿 —— 两腿平行、向外岔开（横向偏移与 SnowboardModel 的鞋位同值，
			// 否则脚会与鞋错开）；屈膝在上面的 skis$poseLegSplit 里绕靴口做。
			placeLeg(self.rightLeg, -SkiStance.BOARD_LEG_SPREAD);
			placeLeg(self.leftLeg, SkiStance.BOARD_LEG_SPREAD);
		} else {
			// 双板：两腿只横向拉开、保持平行
			placeLeg(self.rightLeg, -SkiStance.legOffsetX());
			placeLeg(self.leftLeg, SkiStance.legOffsetX());
		}

		// 手臂：拿滑雪杖的那只手播一次撑杖、另一只手按板块摆固定姿势（见下面 freeArmXrot / freeArmRoll：
		// 双板没拿杖的手向前抬 14°，单板只向两侧抬 16°）
		if (!state.isUsingItem) {
			float armRot = ARM_REST;   // 默认：杖放低一点（不举到最高）
			if (state instanceof AvatarRenderState avatar && isLocalAvatar(avatar)) {
				int left = SkiState.swingLeft(avatar.id);
				if (left > 0) {
					// 一次撑杖：0 → RAISE_FRACTION 先举到最高，然后往下摆，最后收回 ARM_REST
					float progress = (SkiState.swingTicks() - left) / (float) SkiState.swingTicks();   // 0 → 1
					float downEnd = RAISE_FRACTION + (1.0F - RAISE_FRACTION) * DOWN_FRACTION;
					if (progress < RAISE_FRACTION) {
						float t = progress / RAISE_FRACTION;
						armRot = Mth.lerp(t * t * (3.0F - 2.0F * t), ARM_REST, ARM_TOP);   // 举起（平滑）
					} else if (progress < downEnd) {
						float t = (progress - RAISE_FRACTION) / (downEnd - RAISE_FRACTION);
						armRot = Mth.lerp(t * t, ARM_TOP, ARM_BOTTOM);   // 下摆：前半程慢、过半后加速扎下去（点杖）
					} else {
						float t = (progress - downEnd) / (1.0F - downEnd);
						armRot = Mth.lerp(t * t * (3.0F - 2.0F * t), ARM_BOTTOM, ARM_REST);   // 收回
					}
				}
			}
			// 手端稍稍向外（正负对称）→ 垂下来的杖是平行线，不会向内倾、穿到腿上
			if (state.rightHandItemStack.getItem() == SkisMod.SKI_POLE) {
				poseArm(self.rightArm, armRot, ARM_OUTWARD_ROLL);
			} else {
				// 没拿杖的手：抬起（双板＝向前 + 向两侧，单板＝只向两侧），否则会走原版的走路摆臂
				poseArm(self.rightArm, freeArmXrot(board), freeArmRoll(board));
			}
			if (state.leftHandItemStack.getItem() == SkisMod.SKI_POLE) {
				poseArm(self.leftArm, armRot, -ARM_OUTWARD_ROLL);
			} else {
				poseArm(self.leftArm, freeArmXrot(board), -freeArmRoll(board));
			}
		}

		// 上身与头：微蹲的另外两条角度（蹲行时保持原版的弯腰与头角，整段不覆盖）。
		// 跳跃那一趟（airBlend = 1）把腿与躯干收到更小的角度（-0.19 / 0.12，只缩一点点）；
		// 其余腾空 airBlend = 0 ＝地面值（落地那一 tick 立刻回地面值）；蹲行优先，躯干 / 头仍保持原版。
		// 单板按 W / S 的“整个人 + 板一起倾”不在模型里做 —— 板是独立提交的模型，只改部件它不跟，
		// 统一在 EntityTiltMixin 里转整个 poseStack。
		if (!state.isCrouching) {
			self.body.xRot = Mth.lerp(airBlend, SkiStance.SQUAT_BODY_LEAN, SkiStance.SQUAT_BODY_LEAN_AIR);   // 躯干前压（与腿的后倾配对＝屈身）
			self.head.xRot = SkiStance.SQUAT_HEAD_PITCH;   // 头随上身略前低（只在站姿生效分支里写）
		}
	}

	/**
	 * 腿的「分两段」开关：站姿生效时只画大腿子部件、不画原版整条腿；不生效时全部交回原版。
	 *
	 * <p><b>为什么用 {@code skipDraw} 而不是 {@code visible}</b>：{@code visible = false} 会连 children
	 * 一起不画（{@code ModelPart#render} 一进门就 return），而大腿恰恰挂在原版腿上当孩子；
	 * {@code skipDraw} 只跳过<b>本部件自己的 cube</b>、children 照常渲染 —— 正是「藏起 12 单位的那根、
	 * 只留 6 单位的大腿」需要的开关。
	 */
	private void skis$poseLegSplit(HumanoidModel<?> self, boolean stance, float airBlend) {
		ModelPart thigh = thighOf(self.rightLeg);
		if (thigh != null) {
			// 玩家模型（createMesh 加了 skis_thigh）：原版整条腿跳过自己的 cube，大腿当孩子照常画
			self.rightLeg.skipDraw = stance;
			self.leftLeg.skipDraw = stance;
			poseThigh(thigh, stance, airBlend);
			poseThigh(thighOf(self.leftLeg), stance, airBlend);
			if (self instanceof PlayerModel playerModel) {
				// 外层裤腿是腿的孩子（vanilla 让它们跟着腿的位姿走），同样只跳过自己的 cube ——
				// 别再单独给 pants 写位置 / 旋转，那会和腿差一帧
				playerModel.leftPants.skipDraw = stance;
				playerModel.rightPants.skipDraw = stance;
			}
			return;
		}
		if (!stance) {
			if (this.skis$armorLegsHidden) {          // 只撤我们自己藏的那次，不碰原版的可见性
				self.leftLeg.visible = true;
				self.rightLeg.visible = true;
				this.skis$armorLegsHidden = false;
			}
			return;
		}
		// 护甲模型（HumanoidArmorModel 等）没有大腿子部件：腿仍是整条 12 单位、绕髋倾，
		// 照样会戳出雪鞋 —— 只能整条腿藏掉；雪鞋本身就盖住了小腿，视觉上不缺。
		if (self instanceof PlayerModel && !this.skis$warnedMissingThigh) {
			// <b>PlayerModel 一定有 thigh</b>（PlayerModelThighMixin 加的）：走到这里说明那个 mixin
			// 没生效，或者它挂的部件名与 SkiStance.THIGH_PART 不一致。这个失败原本是<b>静默</b>的
			// （玩家只看到「腿没了」、日志一片干净），所以在这里打一次 warn 让它可诊断。只打一次。
			this.skis$warnedMissingThigh = true;
			SkisMod.LOGGER.warn("[skis] 玩家模型的腿上找不到「大腿子部件」（部件名 \"{}\"）—— 滑雪站姿会退化成"
					+ "「整条腿藏起来」。检查 PlayerModelThighMixin 是否还在 skis.client.mixins.json 里、"
					+ "以及它挂的部件名是否等于 SkiStance.THIGH_PART。", SkiStance.THIGH_PART);
		}
		self.leftLeg.visible = false;
		self.rightLeg.visible = false;
		this.skis$armorLegsHidden = true;
	}

	/**
	 * 这个 avatar 是不是<b>本机玩家自己</b>（{@link Minecraft#player}）。
	 *
	 * <p>只有本机玩家的 id 会被写进 {@link SkiState}（右键触发、无 S2C 包），所以摆臂动画只对本机 avatar 读表；
	 * 远端玩家<b>不摆臂</b>（见类注释里的已知限制）。
	 */
	private static boolean isLocalAvatar(AvatarRenderState avatar) {
		LocalPlayer self = Minecraft.getInstance().player;
		return self != null && avatar.id == self.getId();
	}

	/** 取腿上的大腿子部件；没有（护甲模型等）返回 null。 */
	private static ModelPart thighOf(ModelPart leg) {
		// 名字从 SkiStance 取（不能引用 PlayerModelThighMixin.THIGH：Mixin 类里不允许非 private 字段）
		return leg.hasChild(SkiStance.THIGH_PART) ? leg.getChild(SkiStance.THIGH_PART) : null;
	}

	/**
	 * 大腿子部件：绕靴口翻（负角＝膝往前顶，与原「腿后倾」同一方向），不生效时藏起来。
	 *
	 * <p>角度在「地面值 {@link SkiStance#SQUAT_LEG_TILT} → 空中值 {@link SkiStance#SQUAT_LEG_TILT_AIR}」
	 * 之间按 {@code airBlend}（0 / 1）二选一：<b>跳跃那一趟为 1</b>（起跳到落地都只是稍微收腿）；
	 * 其余腾空（含坡上滑落 / 掉落）为 0 ＝ 地面姿势（见 {@code SkiAir.airPoseBlend}）。
	 */
	private static void poseThigh(ModelPart thigh, boolean stance, float airBlend) {
		thigh.visible = stance;
		thigh.xRot = stance
				? Mth.lerp(airBlend, SkiStance.SQUAT_LEG_TILT, SkiStance.SQUAT_LEG_TILT_AIR)
				: 0.0F;
	}

	/** 空手时两臂的前后角：单板不前抬（0），双板向前微抬。 */
	private static float freeArmXrot(boolean board) {
		return board ? FREE_ARM_XROT : SKI_FREE_ARM_XROT;
	}

	/** 空手时两臂向两侧抬起的角度（右臂取正、左臂取负）。 */
	private static float freeArmRoll(boolean board) {
		return board ? FREE_ARM_ROLL : SKI_FREE_ARM_ROLL;
	}

	/** 把一条手臂摆成撑杖姿势：xRot 是划动角，zRot 控制手端朝外还是朝内。 */
	private static void poseArm(ModelPart arm, float xRot, float zRot) {
		arm.xRot = xRot;
		arm.yRot = 0.0F;
		arm.zRot = zRot;
	}

	/**
	 * 把一条腿摆到「向外平移后的位置」，朝向全部归零（平行）。
	 *
	 * <p>屈膝<b>不在这里</b>：腿本身仍是原版那条（枢轴恒在 {@link #LEG_PIVOT_Y}（12）、{@code yScale} 恒 1.0），
	 * 角度归零是为了让「髋 → 靴口」这一段保持竖直；蹲的观感全由挂在它下面的大腿子部件
	 * （{@link #poseThigh}）绕靴口翻出来 —— 这样脚底永远在板面上，膝是唯一被顶出去的东西。
	 */
	private static void placeLeg(ModelPart leg, float x) {
		leg.x = x;
		leg.y = LEG_PIVOT_Y;
		leg.z = 0.0F;
		leg.xRot = 0.0F;
		leg.yRot = 0.0F;
		leg.zRot = 0.0F;
	}

}
