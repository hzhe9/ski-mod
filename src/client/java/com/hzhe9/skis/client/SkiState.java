package com.hzhe9.skis.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Player state used by the gliding stance, stored per entity id:
 * <ul>
 *   <li>{@code onGround}: read once per tick from the real entity by {@code ClientTickEvents.END_CLIENT_TICK};</li>
 *   <li>{@code lastStroke}: the stroke amplitude of the last frame before take-off (remembered across frames,
 *       used to freeze the pose in the air).</li>
 * </ul>
 *
 * <p>Why it is not hung on the render state: entity render state instances are rebuilt every frame, so fields
 * written on them neither keep their value across frames nor are guaranteed to be the same instance that
 * {@code setupAnim} sees (that is exactly where this went wrong before — the airborne check kept reading the
 * default "on ground" value, so the arms kept swinging with the walking animation in mid-air).
 */
public final class SkiState {
	private static final int MAX_ENTRIES = 512;
	/** Arms only resume swinging after this many consecutive ground ticks (0.25 s) — a momentary touch when jumping right after landing does not count. */
	private static final int GROUND_TICKS_TO_RESUME = 5;

	private static final Map<Integer, Boolean> ON_GROUND = new ConcurrentHashMap<>();
	private static final Map<Integer, Integer> GROUNDED_TICKS = new ConcurrentHashMap<>();
	private static final Map<Integer, Float> LAST_STROKE = new ConcurrentHashMap<>();

	private SkiState() {
	}

	public static void setOnGround(int entityId, boolean onGround) {
		if (onGround) {
			GROUNDED_TICKS.merge(entityId, 1, Integer::sum);
		} else {
			GROUNDED_TICKS.put(entityId, 0);
		}
		ON_GROUND.put(entityId, onGround);
	}

	/** Without a record, treat it as "on ground" (does not affect players without skis). */
	public static boolean onGround(int entityId) {
		return ON_GROUND.getOrDefault(entityId, Boolean.TRUE);
	}

	/** Stable landing: ground contact has reached the threshold. Returns false when jumping right after landing (touched only for an instant). */
	public static boolean stablyGrounded(int entityId) {
		return GROUNDED_TICKS.getOrDefault(entityId, 0) >= GROUND_TICKS_TO_RESUME;
	}

	/** While on the ground, record the amplitude every frame and return it. */
	public static float rememberStroke(int entityId, float stroke) {
		LAST_STROKE.put(entityId, stroke);
		return stroke;
	}

	/** While airborne, recall the amplitude of the last frame before take-off (falls back to the passed-in value when nothing is recorded). */
	public static float recallStroke(int entityId, float fallback) {
		return LAST_STROKE.getOrDefault(entityId, fallback);
	}

	/** Clear everything once too many entities have been seen, to avoid accumulating over time. */
	public static void prune() {
		if (ON_GROUND.size() > MAX_ENTRIES) {
			ON_GROUND.clear();
			GROUNDED_TICKS.clear();
			LAST_STROKE.clear();
		}
	}
}
