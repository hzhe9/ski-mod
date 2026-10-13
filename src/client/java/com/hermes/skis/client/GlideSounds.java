package com.hermes.skis.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.hermes.skis.SkiGlide;
import com.hermes.skis.SkisMod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * <b>滑行音效</b>（纯客户端）：雪面上的切雪声 + 耳边的风声，两者的音量都随速度加大。
 *
 * <ul>
 *   <li><b>风</b>：一条<b>循环</b>的 {@link SoundInstance}（原版 {@code ELYTRA_FLYING} 那阵风声 ——
 *       它本身就是可无缝循环的），音量 = 速度 × {@link #WIND_PER_SPEED}（夹到 {@link #WIND_MAX}），
 *       跟着玩家走、不再滑行时自动停。用循环实例而不是每隔几 tick 播一次，才不会有断断续续的接缝。</li>
 *   <li><b>雪</b>：每到时间就播一声 {@code SNOW_STEP}（原版雪地脚步，就是雪面的"沙沙"切雪声），
 *       <b>速度越快间隔越短、音量越大</b>（{@link #SNOW_INTERVAL_BASE} → {@link #SNOW_INTERVAL_MIN} tick）。</li>
 * </ul>
 *
 * <p>触发条件与雪粒子同一条：穿着板、在雪 / 冰面上、脚底离地 {@link #GROUND_RANGE} 格以内、有速度。
 * 走近路时不响（{@link #MIN_SPEED}）；不带板不响。
 */
public final class GlideSounds {
	/** 风：音量 = 速度 × 这个系数。 */
	private static final double WIND_PER_SPEED = 0.9D;
	/** 风的最大音量（别再大了，盖过别的音效）。 */
	private static final float WIND_MAX = 0.55F;
	/** 起播音量：太小会有"咔"的一下，给一个不为 0 的初值。 */
	private static final float WIND_START_VOLUME = 0.03F;
	/** 雪声（切雪）用的音效：原版雪地脚步。 */
	private static final SoundEvent SNOW_SOUND = SoundEvents.SNOW_STEP;
	/** 慢速时每多少 tick 响一声。 */
	private static final int SNOW_INTERVAL_BASE = 6;
	/** 速度每快 1 格/tick，间隔少多少 tick（夹到下限）。 */
	private static final double SNOW_INTERVAL_PER_SPEED = 9.0D;
	/** 最快时的间隔（tick）：2 tick 一声 ≈ 连成一片沙沙声。 */
	private static final int SNOW_INTERVAL_MIN = 2;
	/** 雪声音量 = 基础 + 速度 × 系数（夹到上限）。 */
	private static final float SNOW_VOLUME_BASE = 0.12F;
	private static final float SNOW_VOLUME_PER_SPEED = 0.5F;
	private static final float SNOW_VOLUME_MAX = 0.7F;
	/** 雪声音高 = 基础 + 速度 × 系数（越快越尖，最多到 1.2）。 */
	private static final float SNOW_PITCH_BASE = 0.95F;
	private static final float SNOW_PITCH_PER_SPEED = 0.25F;
	/** 低于这个速度（格/tick）不出声。 */
	private static final double MIN_SPEED = 0.10D;
	/** 脚底离地不超过这个高度（格）才算"在滑"——与雪粒子同一条判定。 */
	private static final double GROUND_RANGE = 1.0D;

	private static final int MAX_ENTRIES = 64;

	/** 实体 id → 那条正在播的风声。 */
	private static final Map<Integer, WindSound> WIND = new ConcurrentHashMap<>();
	/** 实体 id → 距离下一声切雪还有几 tick。 */
	private static final Map<Integer, Integer> SNOW_TIMER = new ConcurrentHashMap<>();

	private GlideSounds() {
	}

	/** 每客户端 tick 跑一次。 */
	public static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null) {
			return;
		}
		if (WIND.size() > MAX_ENTRIES) {
			WIND.clear();
			SNOW_TIMER.clear();
		}
		for (Player player : level.players()) {
			int id = player.getId();
			if (!isGliding(player)) {
				WIND.remove(id);        // 循环实例自己会在下一 tick 收到 stop()（见 WindSound#tick）
				SNOW_TIMER.remove(id);
				continue;
			}
			if (!WIND.containsKey(id)) {
				WindSound wind = new WindSound(player);
				WIND.put(id, wind);
				client.getSoundManager().play(wind);
			}
			int timer = SNOW_TIMER.getOrDefault(id, 0) - 1;
			if (timer <= 0) {
				playSnow(level, player);
				timer = snowInterval(player.getDeltaMovement().horizontalDistance());
			}
			SNOW_TIMER.put(id, timer);
		}
	}

	/** 在滑吗：穿着板、在雪 / 冰面上、脚底离地一格以内、有速度。 */
	static boolean isGliding(Player player) {
		if (!SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			return false;
		}
		if (player.getDeltaMovement().horizontalDistance() < MIN_SPEED) {
			return false;
		}
		Level level = player.level();
		return SkiGlide.isOnSnowOrIce(level, player.blockPosition())
				&& SkiGlide.groundDistance(player, GROUND_RANGE) <= GROUND_RANGE;
	}

	/** 速度越快间隔越短。 */
	private static int snowInterval(double speed) {
		int interval = (int) Math.round(SNOW_INTERVAL_BASE - speed * SNOW_INTERVAL_PER_SPEED);
		return Mth.clamp(interval, SNOW_INTERVAL_MIN, SNOW_INTERVAL_BASE);
	}

	/** 一声切雪：音量与音高都随速度抬。 */
	private static void playSnow(ClientLevel level, Player player) {
		double speed = Math.min(player.getDeltaMovement().horizontalDistance(), 1.0D);
		float volume = (float) Math.min(SNOW_VOLUME_BASE + speed * SNOW_VOLUME_PER_SPEED, SNOW_VOLUME_MAX);
		float pitch = SNOW_PITCH_BASE + (float) speed * SNOW_PITCH_PER_SPEED;
		level.playLocalSound(player.getX(), player.getY(), player.getZ(), SNOW_SOUND, SoundSource.PLAYERS,
				volume, pitch, false);
	}

	/**
	 * 耳边那阵风：一条循环的声音实例，每 tick 跟着玩家并更新音量；不再滑行就停。
	 * （照原版 {@code ElytraOnPlayerSoundInstance} 的写法：{@code looping = true} + {@code delay = 0}。）
	 */
	private static final class WindSound extends AbstractTickableSoundInstance {
		private final Player player;
		private int time;

		private WindSound(Player player) {
			super(SoundEvents.ELYTRA_FLYING, SoundSource.PLAYERS, SoundInstance.createUnseededRandom());
			this.player = player;
			this.looping = true;
			this.delay = 0;
			this.volume = WIND_START_VOLUME;
			this.pitch = 1.0F;
		}

		@Override
		public void tick() {
			this.time++;
			// 前 20 tick 先让它起播（和原版鞘翅一样，避免瞬间开关时的爆音）
			if (this.player.isRemoved() || (this.time > 20 && !isGliding(this.player))) {
				stop();
				return;
			}
			this.x = this.player.getX();
			this.y = this.player.getY();
			this.z = this.player.getZ();
			double speed = this.player.getDeltaMovement().horizontalDistance();
			this.volume = (float) Mth.clamp(speed * WIND_PER_SPEED, 0.0D, (double) WIND_MAX);
		}
	}
}
