package com.hzhe9.skis;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * <b>穿着滑雪板、落地前 60 tick 内踩过雪面 = 免除摔落伤害</b>：板当缓冲，怎么从坡上飞出去摔在雪里都不掉血。
 *
 * <p>用 Fabric 的 {@link ServerLivingEntityEvents#ALLOW_DAMAGE} 事件而不是 mixin：摔落伤害最终会走
 * {@code Entity#hurt → hurtOrSimulate → hurtServer}（26.2 实测的调用链），事件就在 {@code hurtServer} 上，
 * 所以这里返回 {@code false} 就能整份伤害取消掉。好处是纯服务端逻辑、写在 {@code src/main} 里，
 * 单机与联机都生效，也不碰任何原版方法。
 *
 * <p>免伤的公共前提三条：① 是玩家；② 脚上穿着我们的板（双板 / 单板，{@link SkisMod#isSkiFeet}）；
 * ③ <b>伤害类型属于摔落</b>（{@link DamageTypeTags#IS_FALL}；别的伤害一律放行）。
 * 满足后再看落点，两条判定<b>取或</b>：
 * <ul>
 *   <li><b>落点本身就是雪 / 冰面</b>（{@link SkiGlide#isSnowLanding}：脚底格、上一格、下两格任一是雪 / 冰）。
 *       落地那一 tick 脚就踩在雪上，本身就属于「踩在雪地上」，所以这条原样保留 —— 也是记录表里没有这个玩家
 *       （从没被 tick 记到）时的兜底。比「能不能滑」的判定宽：石头上铺一层雪（0.125 格）也算雪面。</li>
 *   <li><b>落地时距上次「真的踩在雪地上」不超过 {@link #SNOW_GRACE_TICKS} 个 tick</b>（60 tick = 3 秒）。
 *       记录由 {@link #register()} 里挂的 {@code END_SERVER_TICK} 钩子维护：玩家穿着板、{@code onGround()}
 *       且脚下是雪 / 冰（同一个 {@code isSnowLanding}）时才把当前 {@code level().getGameTime()}
 *       写进 {@link #LAST_SNOW_TICK}。于是「从雪坡冲下来、腾空两秒、摔在石头上」免伤；
 *       「在石头地上站够 3 秒再摔」照旧掉血。不用「离地几格内」或射线判贴地 —— 判据的字面是「踩在雪地上过」。</li>
 * </ul>
 *
 * <p>命中时顺手 {@code resetFallDistance()}，免得下一次落地接着累计。
 *
 * <p>记录表的清理策略（主用「靠时间自然过期」，因为它不需要清表就自带正确性）：① 比对只接受
 * {@code 0 ≤ now - last ≤ SNOW_GRACE_TICKS} 的时间戳，<b>所以退出 / 换维度时残留的旧 id 记录会自然失效</b>，
 * 不用靠清表保证正确性；② 顺手补四道：脱板当场 {@code remove}、断线 {@code remove}、<b>死亡 / 重生
 * {@code remove}</b>（重生地点与死亡前无关，不能拿死前 3 秒的雪面记录免掉重生后的摔伤）、
 * 超 {@link #MAX_ENTRIES} 整表 {@code clear()}（与 {@code SkiBoost} 同一套写法；本类用的是裸
 * {@code ConcurrentHashMap}，不走 {@link com.hzhe9.skis.util.IdMap} 的自清）。
 */
public final class SnowLanding {
	/** 落地时距上次「真的踩在雪地上」多少 tick 内仍算雪地缓冲（60 tick = 3 秒）。 */
	public static final int SNOW_GRACE_TICKS = 60;

	/** 记录表上限；超了整表清空（与 {@code SkiBoost.MAX_ENTRIES} 同为 64，本类不走 {@link com.hzhe9.skis.util.IdMap}）。 */
	private static final int MAX_ENTRIES = 64;

	/** 玩家 id → 最后一次「穿着板、站在地上、脚下是雪 / 冰」的游戏时间（tick）。 */
	private static final Map<Integer, Long> LAST_SNOW_TICK = new ConcurrentHashMap<>();

	private SnowLanding() {
	}

	public static void register() {
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(SnowLanding::allowDamage);

		// 每服务器 tick 记一次「此刻真的踩在雪地上」：穿板 + onGround + 脚下是雪 / 冰。
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Integer id = player.getId();
				if (!SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
					LAST_SNOW_TICK.remove(id);   // 没穿板不记，也免得脱板后还在吃上个记录
					continue;
				}
				if (player.onGround() && SkiGlide.isSnowLanding(player.level(), player.blockPosition())) {
					LAST_SNOW_TICK.put(id, player.level().getGameTime());
				}
			}
			if (LAST_SNOW_TICK.size() > MAX_ENTRIES) {
				LAST_SNOW_TICK.clear();
			}
		});

		// 断线就忘掉，别留着旧 id 的时间戳。
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				LAST_SNOW_TICK.remove(handler.getPlayer().getId()));

		// 死亡 / 重生：id 不变（重生后还是同一个玩家），但**地点与死亡前完全无关** —— 不清掉的话，
		// 「死前 3 秒还在雪上、重生点是一块石头地」的玩家落下去照样免摔伤（60 tick 窗口跨了生死）。
		// 也顺手清掉旧实体的那份（旧版服务端换人时会 new 一个 ServerPlayer）。
		ServerPlayerEvents.AFTER_RESPAWN.register((newPlayer, oldPlayer, alive) -> {
			LAST_SNOW_TICK.remove(newPlayer.getId());
			if (oldPlayer != null) {
				LAST_SNOW_TICK.remove(oldPlayer.getId());
			}
		});
	}

	/** 返回 false ＝ 取消这份伤害。 */
	private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
		if (!(entity instanceof Player player)) {
			return true;
		}
		if (!SkisMod.isSkiFeet(player.getItemBySlot(EquipmentSlot.FEET))) {
			return true;
		}
		if (!source.is(DamageTypeTags.IS_FALL)) {
			return true;   // 只免摔伤
		}
		long now = player.level().getGameTime();
		Long last = LAST_SNOW_TICK.get(player.getId());
		// now - last 可能为负（换维度 / 世界时间回退），那种旧记录不算数
		boolean recentlyOnSnow = last != null && now - last >= 0L && now - last <= SNOW_GRACE_TICKS;
		if (!SkiGlide.isSnowLanding(player.level(), player.blockPosition()) && !recentlyOnSnow) {
			return true;   // 落点不是雪面、落地前 60 tick 内也没踩过雪：照旧摔伤
		}
		player.resetFallDistance();   // 顺手把累计高度清掉，免得下一次落地接着算
		return false;
	}
}
