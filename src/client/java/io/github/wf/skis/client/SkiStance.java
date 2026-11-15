package io.github.wf.skis.client;

/**
 * 滑行站姿的几何常量：腿部（以及脚上跟着腿走的雪板模型）横向平移多少。
 *
 * <p>两处必须用同一个值，否则脚分开了、板还在原地：
 * <ul>
 *   <li>{@code HumanoidModelMixin} 把玩家模型的左右腿各平移 {@code legOffsetX()}；</li>
 *   <li>{@code SkiBoardModel} 把雪鞋 + 板身也建在同样的横向上。</li>
 * </ul>
 */
public final class SkiStance {
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
	public static final float BOARD_LEG_SPREAD = 2.8F;   // = 双板的 legOffsetX()（1.9 + 0.9），两板腿部同宽

	/**
	 * 穿板站姿「微蹲」的三个角度旋钮之 —— <b>双腿后倾角</b>（弧度，负值＝脚相对髋往前、下半身向后斜）。
	 *
	 * <p>原版 biped 的腿是<b>单节</b> box（一根从枢轴往下 12 单位的方块，没有膝关节），
	 * <b>无法屈膝</b> —— 所以蹲的观感只能由角度表达：腿往后斜、上身再往前压、头略低，
	 * 三者合起来读作「屈着身子站在板上」。<b>腿长与枢轴高度都是原版的</b>
	 * （{@code yScale} 恒 1.0、{@code leg.y} 恒 12）：不缩腿、不挪任何 y、不写任何 scale，
	 * 脚底位置与不穿板时完全一致 —— 板不会浮空，也不会插进雪里。
	 *
	 * <p>两块板共用这一个值（曾经的单板 {@code BOARD_LEG_TILT} / 双板 {@code SKI_LEG_TILT}
	 * 两个同值常量已删除：同一个旋钮不该记两处）。-0.15 → -0.20 是把「微蹲」的意图补回来的量：
	 * 脚相对髋后移 ≈ 12 × |sin 0.05| ≈ 0.6 模型单位（≈0.04 格）、抬高 ≈ 0.015 单位，视觉可忽略。
	 */
	public static final float SQUAT_LEG_TILT = -0.20F;

	/**
	 * 穿板站姿「微蹲」之 —— <b>上身（躯干）前倾角</b>（弧度，正值＝向前压）。
	 *
	 * <p>与 {@link #SQUAT_LEG_TILT} 配对：下半身往后、上半身往前，才有「压着刃」的屈身感；
	 * 只做腿后倾会读成「直挺挺地往后倒」。0.05 → 0.12（原来的 {@code GLIDE_LEAN} 已删除，统一用这一个）。
	 */
	public static final float SQUAT_BODY_LEAN = 0.12F;

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
