package com.hzhe9.skis.client;

import com.hzhe9.skis.util.IdMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * <b>滑行音效</b>（纯客户端）：雪面上滑行时的<b>切雪声</b> —— 每到时间就播一声 {@code SNOW_STEP}
 * （原版雪地脚步，就是雪面的“沙沙”声），<b>速度越快间隔越短、音量越大、音高越尖</b>
 * （{@link #SNOW_INTERVAL_BASE} → {@link #SNOW_INTERVAL_MIN} tick）。
 *
 * <p>几何条件与雪粒子<b>同源</b>（穿板 + 在雪 / 冰面上 + 有速度 + 脚底离地多远），但<b>门槛各自一个常量</b>：
 * 声音是 <b>1.0 格</b>（{@link #GROUND_RANGE}）/ <b>0.10</b> 格/tick（{@link #MIN_SPEED}），
 * 雪粒子是 <b>1.5 格</b>（{@code SnowSpray.AIR_RANGE}）/ <b>0.06</b> —— 两套观感各按自己的调，
 * 改一边不会动另一边，别当成「同一条判据」。判据本身在 {@link GlideAudioGate#isGliding}，
 * 与另外两条静音 mixin 共用（见 {@link GlideAudioGate}）。
 *
 * <p><b>没有风声</b>：风那层是一段循环音，滑行时一直占着一个声道、还会盖住切雪声的细节 —— 只保留切雪声这一条。
 */
public final class GlideSounds {
	/** 切雪声用的音效：原版雪地脚步。 */
	private static final SoundEvent SNOW_SOUND = SoundEvents.SNOW_STEP;
	/** 慢速时每多少 tick 响一声。 */
	private static final int SNOW_INTERVAL_BASE = 8;
	/** 速度每快 1 格/tick，间隔少多少 tick（夹到下限）。 */
	private static final double SNOW_INTERVAL_PER_SPEED = 5.0D;
	/** 最快时的间隔（tick）：再密就听成一串电流声 —— 4 tick 一声还留得住单次可辨的切雪颗粒感。 */
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
	/** 脚底离地不超过这个高度（格）才算“在滑”—— <b>声音自己的门槛</b>，雪粒子用 1.5 格（{@code SnowSpray.AIR_RANGE}），两者不联动。 */
	private static final double GROUND_RANGE = 1.0D;

	/**
	 * <b>距离剔除</b>（平方，格²）：离相机超过 32 格（{@code 32² = 1024}）的玩家不推进计时、不出声。
	 *
	 * <p>与 {@code SnowSpray.CULL_DISTANCE_SQR} 同值，理由也一样：{@code playLocalSound} 确实会随距离衰减，
	 * 但衰减到听不见之后仍然每 tick 跑一遍判据（{@link GlideAudioGate#isGliding} 里有一次
	 * {@code SkiGlide.groundDistance} 射线）—— 那是白烧的。剔除阈值取「反正听不见」的距离，
	 * 所以听感一点不变。
	 */
	private static final double CULL_DISTANCE_SQR = 1024.0D;

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
			silence();   // 暂停时立刻收声（原因见 silence 的注释）
			return;
		}
		Vec3 camera = client.gameRenderer.mainCamera().position();
		for (Player player : level.players()) {
			int id = player.getId();
			if (player.distanceToSqr(camera) > CULL_DISTANCE_SQR) {
				SNOW_TIMER.remove(id);   // 太远：听不见，计时也别留着（滑近时当作从头起算）
				continue;
			}
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
	 * 暂停时收声：<b>只清我们自己那张计时表</b>，不动全局声道。
	 *
	 * <p>暂停（单人 Esc / 失焦）时游戏仍在渲染、{@code Minecraft#tick()} 照跑（所以本类每 tick 还是被调用），
	 * 但声音引擎拿到的参数是「已暂停」—— 她只推进音乐，<b>不再推进游戏内的声音</b>：
	 * 每几 tick 播一次的切雪声会一直继续播下去。所以暂停时必须停止<b>继续发声</b>：清掉计时表就够 ——
	 * 我们用的是 {@code playLocalSound} 的一次性声音，下一次想播得等计时表重新攒满。
	 *
	 * <p><b>为什么不调 {@code SoundManager#stop}</b>：那要传（音效 id, 声道）这一对<b>全局</b>键 ——
	 * 我们用的是原版 {@code SoundEvents.SNOW_STEP} + {@code SoundSource.PLAYERS}，一停就会把场上
	 * 任何实体 / 别的玩家正在播的雪地脚步一起掐掉。
	 */
	private static void silence() {
		SNOW_TIMER.clear();
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
