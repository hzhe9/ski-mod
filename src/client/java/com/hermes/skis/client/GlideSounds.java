package com.hermes.skis.client;

import com.hermes.skis.util.IdMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

/**
 * <b>滑行音效</b>（纯客户端）：雪面上滑行时的<b>切雪声</b> —— 每到时间就播一声 {@code SNOW_STEP}
 * （原版雪地脚步，就是雪面的“沙沙”声），<b>速度越快间隔越短、音量越大、音高越尖</b>
 * （{@link #SNOW_INTERVAL_BASE} → {@link #SNOW_INTERVAL_MIN} tick）。
 *
 * <p>触发条件与雪粒子同一条：穿着板、在雪 / 冰面上、脚底离地 {@link #GROUND_RANGE} 格以内、有速度。
 * 太慢不响（{@link #MIN_SPEED}）；不带板不响。判据本身在 {@link GlideAudioGate#isGliding}，
 * 与另外两条静音 mixin 共用（见 {@link GlideAudioGate}）。
 *
 * <p><b>没有风声</b>：原先那条循环的 {@code ELYTRA_FLYING} 风按用户 2026-10-13 的要求去掉，本类只剩切雪声。
 */
public final class GlideSounds {
	/** 切雪声用的音效：原版雪地脚步。 */
	private static final SoundEvent SNOW_SOUND = SoundEvents.SNOW_STEP;
	/** 慢速时每多少 tick 响一声。 */
	private static final int SNOW_INTERVAL_BASE = 8;
	/** 速度每快 1 格/tick，间隔少多少 tick（夹到下限）。 */
	private static final double SNOW_INTERVAL_PER_SPEED = 5.0D;
	/** 最快时的间隔（tick）：别再密了，太密听上去像电流声（用户 2026-10-13 反馈「太密集」）。 */
	private static final int SNOW_INTERVAL_MIN = 4;
	/** 雪声音量 = 基础 + 速度 × 系数（夹到上限）。 */
	private static final float SNOW_VOLUME_BASE = 0.12F;
	private static final float SNOW_VOLUME_PER_SPEED = 0.5F;
	private static final float SNOW_VOLUME_MAX = 0.7F;
	/** 雪声音高 = 基础 + 速度 × 系数（越快越尖，最多到 1.2）。 */
	private static final float SNOW_PITCH_BASE = 0.95F;
	private static final float SNOW_PITCH_PER_SPEED = 0.25F;
	/** 低于这个速度（格/tick）不出声。 */
	private static final double MIN_SPEED = 0.10D;
	/** 脚底离地不超过这个高度（格）才算“在滑”——与雪粒子同一条判定。 */
	private static final double GROUND_RANGE = 1.0D;

	/** 实体 id → 距离下一声切雪还有几 tick。 */
	private static final IdMap<Integer> SNOW_TIMER = new IdMap<>();

	private GlideSounds() {
	}

	/** 每客户端 tick 跑一次。 */
	public static void tick(Minecraft client) {
		ClientLevel level = client.level;
		if (level == null) {
			return;
		}
		if (client.isPaused()) {
			silence(client);   // 暂停时立刻收声（原因见 silence 的注释）
			return;
		}
		for (Player player : level.players()) {
			int id = player.getId();
			if (!GlideAudioGate.isGliding(player, MIN_SPEED, GROUND_RANGE)) {
				SNOW_TIMER.remove(id);
				continue;
			}
			int timer = SNOW_TIMER.getOrDefault(id, 0) - 1;
			if (timer <= 0) {
				playSnow(level, player);
				timer = snowInterval(player.getDeltaMovement().horizontalDistance());
			}
			SNOW_TIMER.put(id, timer);
		}
	}

	/**
	 * 暂停时立刻收声。
	 *
	 * <p>暂停（单人 Esc / 失焦）时游戏仍在渲染、{@code Minecraft#tick()} 照跑（所以本类每 tick 还是被调用），
	 * 但声音引擎拿到的参数是「已暂停」——她只推进音乐，<b>不再推进游戏内的声音</b>：
	 * 每几 tick 播一次的切雪声会一直继续播下去。所以暂停时必须<b>主动停</b>：
	 * {@code SoundManager#stop(音效 id, 声道)} 把已经在播的那一声（以及刚排队的）一起掐掉，
	 * 状态表清空，解暂停后重新起算。
	 */
	private static void silence(Minecraft client) {
		SNOW_TIMER.clear();
		client.getSoundManager().stop(SNOW_SOUND.location(), SoundSource.PLAYERS);
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
}
