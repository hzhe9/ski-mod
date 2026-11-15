package io.github.wf.skis.client;

import io.github.wf.skis.SkiGlide;
import io.github.wf.skis.SkisMod;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

/**
 * <b>「这条声音该不该响」的唯一判据</b> —— 脚步、落地、滑行切雪三条音效共用一份条件。
 *
 * <p>静音这件事本身不产生任何数值：判据只回答「脚上是不是穿着我们的板」「脚下这一格是不是雪 / 冰」
 * 「现在是不是在雪面上滑」。三处 mixin 与 {@link GlideSounds} 各自只负责自己的注入点 / 播放逻辑，
 * 条件本身收在这里，避免同一个判定在四个文件里各写一遍之后慢慢走偏（比如某处漏掉「只在玩家身上」，
 * 就会把别的实体也静音）。
 *
 * <p>注入口径不受影响：{@code Entity#vibrationAndSoundEffectsFromBlock}（连 PresenceFootsteps 的钩子一起哑）、
 * {@code Player#playStepSound} 兜底、{@code LivingEntity#causeFallDamage} 整段取消、
 * {@code LivingEntity#playBlockFallSound} 兜底 —— 这些都是「判据为真就不响」。</p>
 */
public final class GlideAudioGate {
	private GlideAudioGate() {
	}

	/** 脚上穿着我们的板（双板 / 单板）；非玩家实体一律不算 —— 静音只针对玩家自己的脚步声。 */
	public static boolean isBoarded(Entity entity) {
		return entity instanceof Player player && isBoarded(player);
	}

	/** 脚上穿着我们的板（双板 / 单板）。 */
	public static boolean isBoarded(Player player) {
		return SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET));
	}

	/** 穿板 + 脚下这一格是雪 / 冰（落地闷响与免摔伤共用同一口径，见 {@link SkiGlide#isOnSnowOrIce}）。 */
	public static boolean isBoardedOnSnow(Entity entity) {
		return entity instanceof Player player
				&& isBoarded(player)
				&& SkiGlide.isOnSnowOrIce(player.level(), player.blockPosition());
	}

	/**
	 * 在雪面上滑行：穿板 + 有速度 + 脚下是雪 / 冰 + 脚底离地不超过 {@code groundRange}。
	 *
	 * <p>两条门槛由调用方给：滑行声用 {@code GlideSounds} 的 {@code MIN_SPEED} / {@code GROUND_RANGE}，
	 * 与雪粒子的 {@code AIR_RANGE} 1.5 格**不联动**（各一个常量，见 {@code docs/tuning.md}）。
	 */
	public static boolean isGliding(Player player, double minSpeed, double groundRange) {
		if (!SkisMod.isSkiMotionActive(player)) {
			return false;   // 没穿板 / 创造飞行：不滑行，也就没有切雪声
		}
		if (player.getDeltaMovement().horizontalDistance() < minSpeed) {
			return false;
		}
		return SkiGlide.isOnSnowOrIce(player.level(), player.blockPosition())
				&& SkiGlide.groundDistance(player, groundRange) <= groundRange;
	}
}
