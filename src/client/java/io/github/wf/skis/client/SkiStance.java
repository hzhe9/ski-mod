package io.github.wf.skis.client;

/**
 * 滑行站姿的几何常量：腿部（以及脚上跟着腿走的雪板模型）横向平移多少。
 *
 * <p>两处必须用同一个值，否则脚分开了、板还在原地：
 * <ul>
 *   <li>{@code HumanoidModelMixin} 把玩家模型的左右腿各平移 {@code legOffsetX()}；</li>
 *   <li>{@code AlpineSkiModel} 把雪鞋 + 板身也建在同样的横向上。</li>
 * </ul>
 */
public final class SkiStance {
	/**
	 * 大腿子部件的名字（{@code PlayerModelThighMixin} 建、{@code HumanoidModelMixin} 取；两边必须同一个值）。
	 *
	 * <p><b>为什么放在这里而不是混入类里</b>：Mixin 类里只允许 {@code private} 字段 —— 非 private 字段会被当成
	 * 「要合并进目标类的成员」交给校验器，非 private 的 static 字面量会直接被
	 * {@code InvalidMixinException: contains non-private static field} 拒掉（1.21.9+ 的校验会因此让整个资源重载失败、
	 * 启动黑屏）。普通类里的 {@code public static final} 才是能跨类共享常量的正确位置。
	 */
	public static final String THIGH_PART = "skis_thigh";

	/** 玩家模型里腿部枢轴的原始横向位置（HumanoidModel/PlayerModel 都是 1.9）。 */
	public static final float BASE_LEG_X = 1.9F;
	/**
	 * 单侧腿向外平移的距离（单位 1/16 格，也就是一个“像素格”）。0.9 ≈ 5.6 cm，
	 * 结果是两腿中心相距 5.6 单位、两块板之间留出约 1.6 单位（≈0.1 格）的间隔。
	 * 想要更宽就加大（原来是 1.4），想贴在一起就设 0。
	 */
	public static final float LEG_SPREAD = 0.9F;

	/**
	 * 单板：整组（板身 + 板头 + 两只固定器 + 鞋）绕 Y 轴转这个角度。
	 *
	 * <p>默认建模朝向是“朝前”（-z，与玩家朝向一致），而单板是<b>横在脚下、与视线方向垂直</b>的，
	 * 所以整组转 90°。想换板头朝哪一侧就把这里改成 -90（腿位常量也要按同样映射换）。
	 */
	public static final float BOARD_YAW_DEGREES = 90.0F;
	public static final float BOARD_YAW_RAD = BOARD_YAW_DEGREES * (float) Math.PI / 180.0F;
	/**
	 * 单板：两条腿（以及它们在板上的雪鞋）在<b>玩家模型坐标</b>里的横向偏移 ——
	 * 两腿<b>平行并列、向外岔开</b>，不做鸭子站姿、不前后错位。
	 *
	 * <p>腿位与 {@code SnowboardModel} 的鞋位必须用同一个值；鞋直接建在模型根节点下（与腿同一坐标系），
	 * 不放进转了 90° 的板组里 —— 否则鞋与腿朝向差 90°，看起来就是“脚歪了”。
	 */
	public static final float BOARD_LEG_SPREAD = BASE_LEG_X + LEG_SPREAD;   // = 双板的 legOffsetX()，两板腿部同宽

	/**
	 * 穿板站姿「微蹲」的三个角度旋钮之 —— <b>大腿绕靴口翻的角度</b>（弧度，负值＝膝往前顶、
	 * 大腿向后上方倒）。
	 *
	 * <p><b>枢轴在靴口（模型 y=18）</b>：可见的腿只有上半段（腿局部 y=0…6，即模型 y=12…18，
	 * 由 {@code PlayerModelThighMixin} 加的大腿子部件画出来），而<b>雪鞋就是竖直的下半段</b>
	 * —— {@code AlpineSkiModel} / {@code SnowboardModel} 的 boot 是 {@code addBox(-2,18,-2,4,6,4)}，
	 * 与腿下半段（模型 y=18…24）同形同位。所以绕靴口翻只把<b>膝盖</b>顶出去，
	 * 脚底、板面、鞋一个都不动：不再有「整条腿绕髋倾、脚底甩出雪鞋」的穿模。
	 *
	 * <p>腿本身仍是原版那条（{@code yScale} 恒 1.0、{@code leg.y} 恒 12、{@code leg.xRot} 归零），
	 * 「髋 → 靴口」这一段保持竖直，蹲的观感全部由这一条角度给出（配合躯干前压 + 头略低）。
	 *
	 * <p>两块板共用这一个值（曾经的单板 {@code BOARD_LEG_TILT} / 双板 {@code SKI_LEG_TILT}
	 * 两个同值常量已删除：同一个旋钮不该记两处）。-0.15 → -0.20 → <b>-0.26</b>（用户「屈膝再加大一点点」）；
	 * 换成绕靴口翻之后这个幅度不再带来穿模，手感与之前一致。空中另用 {@link #SQUAT_LEG_TILT_AIR}。
	 */
	public static final float SQUAT_LEG_TILT = -0.26F;

	/**
	 * <b>空中</b>（跳跃那一趟）的大腿角度（弧度，负值＝膝往前顶）：<b>-0.19 ≈ 地面 -0.26 的 73%</b> ——
	 * 只是「缩一点点」，不是站立（曾经收到 -0.06 ≈ 地面的 1/4，用户嫌太小：看起来像站在板上）。
	 *
	 * <p>地面用 {@link #SQUAT_LEG_TILT}，渲染侧（{@code HumanoidModelMixin}）按
	 * {@code SkiAir.airPoseBlend(id)} 在两者之间 {@code Mth.lerp}。blend <b>只有 0 / 1</b>：
	 * 判据只有一条 —— <b>这一趟腾空里起过跳</b>（{@code SkiAir.AirState#jumpedThisAir}）→ 1，
	 * 其余一切（含非跳跃的滑落 / 掉落、空翻但没起跳）→ 0（<b>地面姿势</b>）。
	 * 没有「离地 &gt; 2 格」那条路、也没有 3 tick 渐变（{@code AIR_POSE_NEAR_GROUND_RANGE} /
	 * {@code AIR_POSE_BLEND_TICKS} / {@code farGroundTicks} 已删除）。
	 * 落地那一 tick {@code jumpedThisAir} 已被落地沿清掉，所以**落地当 tick 就回地面值**。
	 */
	public static final float SQUAT_LEG_TILT_AIR = -0.19F;

	/**
	 * 穿板站姿「微蹲」之 —— <b>上身（躯干）前倾角</b>（弧度，正值＝向前压）。
	 *
	 * <p>与 {@link #SQUAT_LEG_TILT} 配对：下半身往后、上半身往前，才有「压着刃」的屈身感；
	 * 只做腿后倾会读成「直挺挺地往后倒」。0.05 → 0.12 → <b>0.16</b>（原来的 {@code GLIDE_LEAN} 已删除，
	 * 统一用这一个）。空中另用 {@link #SQUAT_BODY_LEAN_AIR}。
	 */
	public static final float SQUAT_BODY_LEAN = 0.16F;

	/**
	 * <b>空中</b>（跳跃那一趟）的躯干前倾角（弧度，正值＝向前压）：<b>0.12 ≈ 地面 0.16 的 75%</b> ——
	 * 与收腿配对，只比地面直一点点（不是站立：曾经是 0.04，用户嫌缩得太小）。
	 * 混合方式与 {@link #SQUAT_LEG_TILT_AIR} 完全相同（同一份 `airPoseBlend`，只有 0 / 1）。
	 */
	public static final float SQUAT_BODY_LEAN_AIR = 0.12F;

	/**
	 * 穿板站姿「微蹲」之 —— <b>头随上身略前低</b>（弧度，正值＝低头；0.10 rad ≈ 5.7°）。
	 *
	 * <p>写在 {@code HumanoidModelMixin} 里站姿<b>生效的那条分支</b>、且只在没蹲行时写
	 * （蹲行整段跳过，保持原版弯腰与头角，不干扰原版渲染）。
	 */
	public static final float SQUAT_HEAD_PITCH = 0.10F;

	private SkiStance() {
	}

	/** 平移后单侧腿（及其脚上的板）距离中线的横向距离。 */
	public static float legOffsetX() {
		return BASE_LEG_X + LEG_SPREAD;
	}
}
