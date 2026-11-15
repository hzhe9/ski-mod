package io.github.wf.skis.client;

import io.github.wf.skis.SkiAir;
import io.github.wf.skis.SkisMod;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * <b>First Person Model 兼容</b>：空翻期间<b>让它别画第一人称身体</b>。
 *
 * <p><b>为什么必须关掉身体，而不是去减对方的 offset</b>：那个模组在
 * {@code WorldRendererMixin#renderEntities} 里调 {@code LogicHandler.updatePositionOffset(entity, partialTick)}
 * 拿到一个<b>世界坐标</b>的 {@code Vec3 offset}，然后把它<b>直加</b>到玩家的 {@code rawPosition} 与
 * {@code xo/yo/zo}、{@code xOld/yOld/zOld} 上，画完再撤。这个 offset 是按<b>未翻转</b>的朝向算的
 * （它不知道我们在绕腰翻），而我们的空翻是相机（{@code CameraFlipMixin}）与第三人称模型
 * （{@code EntityTiltMixin}）绕腰转 —— 于是翻到 ≈90° 时这个世界坐标偏移相对身体错开了整个翻转角，
 * 身体被搬到镜头上；0° / 180° 恰好对得上，所以只在翻的中段出现。
 * 那个 offset 是「世界坐标直加、按未翻转朝向算的」，从外面**无法可靠抵消**（不知道它内部算了什么，
 * 也不该替对方改渲染矩阵），所以正解是翻的那几帧**整段不画身体**——这也是它的公开 API 支持的用法。
 *
 * <p><b>为什么用反射而不是编译期依赖</b>：First Person Model 不是本工程的依赖（玩家自己丢进 mods 的），
 * 任何 {@code compileOnly} / {@code implementation} 都会让别人的构建挂掉。反射只按<b>字符串</b>找类，
 * 拼法同 {@link ShoulderSurfingCompat}：对方不在（或 API 改名 / 版本换了包名）就<b>一次性</b>钉成「没接兼容」，
 * 之后一行代码都不碰它，本模组行为与没装该模组时<b>完全一致</b>。
 *
 * <p><b>接法</b>：往 {@code FirstPersonAPI#getActivationHandlers()} 返回的那个 {@code List} 里塞一个
 * {@link Proxy}（实现的接口就是 {@code ActivationHandler}），对方的渲染逻辑会按帧问
 * {@code preventFirstperson()}，我们答「本地玩家正在空翻」＝true。
 * 只有 {@code preventFirstperson()} 这颗开关被我们改，其它方法（含 {@code toString/hashCode/equals}）
 * 一律走本类写好的最小实现 —— 对方内部若拿它当 Map 的键或打日志也不会炸。
 *
 * <p><b>失败即静默</b>：类不在 / 接口变了 / 那个 List 不可改，任何一步抛异常都只记<b>一条</b>
 * {@code LOGGER.warn} 然后永久关掉这条路；<b>绝不每帧抛异常</b>（每帧抛栈会直接把帧率打死），也不让游戏崩。
 */
public final class FirstPersonModelCompat {
	/** 对方模组的 mod id（{@code FabricLoader.isModLoaded} 用）。 */
	private static final String MOD_ID = "firstperson";

	/** 对方 API 入口类（与 jar 里 {@code javap} 出来的全名逐字一致）。 */
	private static final String API_CLASS_NAME = "dev.tr7zw.firstperson.api.FirstPersonAPI";

	/** 对方的回调接口（与 jar 里 {@code javap} 出来的全名逐字一致）。 */
	private static final String HANDLER_CLASS_NAME = "dev.tr7zw.firstperson.api.ActivationHandler";

	/** 接口上唯一的抽象方法名（{@code boolean preventFirstperson()}）。 */
	private static final String PREVENT_METHOD_NAME = "preventFirstperson";

	/** 是否已经装过 / 试过（无论成败只试一次）。 */
	private static boolean installed;
	/** 我们自己这个 handler 的标记名（{@code toString} 用，日志里能认出来）。 */
	private static final String HANDLER_NAME = "skis:flip-body-gate";

	private FirstPersonModelCompat() {
	}

	/**
	 * 客户端初始化时调一次（{@link SkisClient#onInitializeClient()} 末尾）。
	 *
	 * <p>对方不在就直接返回（不 {@code Class.forName}、不占内存）；在才去解析 API 并注册 Proxy。
	 * 重复调用无副作用。
	 */
	public static void install() {
		if (installed) {
			return;
		}
		installed = true;
		try {
			if (!FabricLoader.getInstance().isModLoaded(MOD_ID)) {
				return;                                     // 没装：零影响
			}
			Class<?> api = Class.forName(API_CLASS_NAME);
			Class<?> handlerInterface = Class.forName(HANDLER_CLASS_NAME);
			Method getHandlers = api.getMethod("getActivationHandlers");
			Object raw = getHandlers.invoke(null);
			if (!(raw instanceof List<?> handlers)) {
				throw new IllegalStateException("getActivationHandlers() 没返回 List");
			}
			Object proxy = newHandler(handlerInterface);
			@SuppressWarnings("unchecked")
			List<Object> mutable = (List<Object>) handlers;
			mutable.add(proxy);
			SkisMod.LOGGER.info("[skis] 已接 First Person Model：空翻期间不画第一人称身体");
		} catch (Throwable t) {
			// 钉成「没接兼容」：不重试、不每帧抛异常，只是不兼容而已
			SkisMod.LOGGER.warn("[skis] First Person Model 兼容未启用（空翻中可能有身体被搬进镜头）：{}", t.toString());
		}
	}

	/**
	 * 用<b>接口自己的 ClassLoader</b> 建一个实现该接口的 {@link Proxy}：mixin 环境里
	 * 用错 ClassLoader {@code Proxy.newProxyInstance} 会 {@code IllegalArgumentException}。
	 */
	private static Object newHandler(Class<?> handlerInterface) {
		InvocationHandler invocation = (self, method, args) -> {
			if (PREVENT_METHOD_NAME.equals(method.getName())) {
				return isFlippingLocalPlayer();
			}
			if ("toString".equals(method.getName()) && (args == null || args.length == 0)) {
				return HANDLER_NAME;
			}
			if ("hashCode".equals(method.getName()) && (args == null || args.length == 0)) {
				return System.identityHashCode(self);
			}
			if ("equals".equals(method.getName()) && args != null && args.length == 1) {
				return self == args[0];
			}
			return null;
		};
		return Proxy.newProxyInstance(handlerInterface.getClassLoader(),
				new Class<?>[] { handlerInterface }, invocation);
	}

	/**
	 * 本地玩家此刻是不是在空翻（{@link SkiAir#isFlipping(int)}）。
	 * 没有本地玩家（主菜单 / 正在换世界）返回 false —— 对方那边就当没我们这个人。
	 */
	private static boolean isFlippingLocalPlayer() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player != null && SkiAir.isFlipping(player.getId());
	}
}
