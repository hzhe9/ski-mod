package com.hzhe9.skis.client;

/**
 * Geometry constants for the gliding stance: how far the legs (and the ski board model on the
 * feet, which follows the legs) are translated sideways.
 *
 * <p>Both places must use the same value, otherwise the feet spread apart while the boards stay put:
 * <ul>
 *   <li>{@code HumanoidModelMixin} translates the player model's left and right leg by {@code legOffsetX()};</li>
 *   <li>{@code SkiBoardModel} builds the boot + board body at the same lateral offset.</li>
 * </ul>
 */
public final class SkiStance {
	/** Original lateral position of the leg pivot in the player model (both HumanoidModel and PlayerModel use 1.9). */
	public static final float BASE_LEG_X = 1.9F;
	/**
	 * Sideways translation of each leg (unit: 1/16 block, i.e. one "pixel"). 0.9 ≈ 5.6 cm;
	 * the two leg centres end up 5.6 units apart and the two boards keep a gap of about
	 * 1.6 units (≈0.1 block). Increase for a wider stance (it used to be 1.4), set 0 to touch.
	 */
	public static final float LEG_SPREAD = 0.9F;

	private SkiStance() {
	}

	/** Lateral distance of each translated leg (and the board on that foot) from the centre line. */
	public static float legOffsetX() {
		return BASE_LEG_X + LEG_SPREAD;
	}
}
