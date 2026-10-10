package com.hermes.skis.client;

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

	/** 单板：双脚横跨在同一块板上，按鸭子站姿（duck stance）反向外撇的角度。 */
	public static final float BOARD_STANCE_DEGREES = 18.0F;
	public static final float BOARD_STANCE_RAD = BOARD_STANCE_DEGREES * (float) Math.PI / 180.0F;
	/**
	 * 单板：整组（板身 + 板头 + 两只固定器 + 鞋）绕 Y 轴转这个角度。
	 *
	 * <p>默认建模朝向是“朝前”（-z，与玩家朝向一致），而单板是<b>横在脚下、与视线方向垂直</b>的，
	 * 所以整组转 90°。想换板头朝哪一侧就把这里改成 -90（腿位常量也要按同样映射换）。
	 */
	public static final float BOARD_YAW_DEGREES = 90.0F;
	public static final float BOARD_YAW_RAD = BOARD_YAW_DEGREES * (float) Math.PI / 180.0F;
	/** 单板：固定器在“板上坐标”（未旋转）里的前后位置——只有 {@code SnowboardModel} 内部用。 */
	public static final float BOARD_BINDING_FRONT_Z = -5.5F;
	public static final float BOARD_BINDING_BACK_Z = 2.5F;
	/**
	 * 单板：两条腿在<b>玩家模型坐标</b>里的位置 = 板上坐标 {@code (x = ±BASE_LEG_X, z = 固定器位置)}
	 * 经上面 90° 旋转换算得到（{@code (x,z) → (z,-x)}），所以腿一定落在板上、与鞋重合。
	 */
	public static final float BOARD_FRONT_LEG_X = -5.5F;
	public static final float BOARD_FRONT_LEG_Z = -1.9F;
	public static final float BOARD_BACK_LEG_X = 2.5F;
	public static final float BOARD_BACK_LEG_Z = 1.9F;

	private SkiStance() {
	}

	/** 平移后单侧腿（及其脚上的板）距离中线的横向距离。 */
	public static float legOffsetX() {
		return BASE_LEG_X + LEG_SPREAD;
	}
}
