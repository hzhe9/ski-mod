package com.hermes.skis;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * <b>穿着滑雪板落到雪面上 = 免除摔落伤害</b>：板当缓冲，怎么从坡上飞出去摔在雪里都不掉血。
 *
 * <p>用 Fabric 的 {@link ServerLivingEntityEvents#ALLOW_DAMAGE} 事件而不是 mixin：摔落伤害最终会走
 * {@code Entity#hurt → hurtOrSimulate → hurtServer}（26.2 实测的调用链），事件就在 {@code hurtServer} 上，
 * 所以这里返回 {@code false} 就能整份伤害取消掉。好处是纯服务端逻辑、写在 {@code src/main} 里，
 * 单机与联机都生效，也不碰任何原版方法。
 *
 * <p>判定条件三条齐了才免伤：① 是玩家；② 脚上穿着我们的板（双板 / 单板，{@link SkisMod#isSkiFeet}）；
 * ③ <b>伤害类型属于摔落</b>（{@link DamageTypeTags#IS_FALL}；别的伤害一律放行）。
 * 落点在雪面上用 {@link SkiGlide#isSnowLanding} —— <b>比「能不能滑」的判定宽</b>：脚底格、上一格、下两格
 * 任一是雪 / 冰就免，所以「石头上铺了一层雪（0.125 格）」也算雪面（用户实测：雪层太薄，落点常跨在雪层与石头之间）。
 */
public final class SnowLanding {
	private SnowLanding() {
	}

	public static void register() {
		ServerLivingEntityEvents.ALLOW_DAMAGE.register(SnowLanding::allowDamage);
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
		if (!SkiGlide.isSnowLanding(player.level(), player.blockPosition())) {
			return true;   // 砸在石头 / 地上照旧摔伤（石头上面铺了雪层的算雪面，见 isSnowLanding）
		}
		player.resetFallDistance();   // 顺手把累计高度清掉，免得下一次落地接着算
		return false;
	}
}
