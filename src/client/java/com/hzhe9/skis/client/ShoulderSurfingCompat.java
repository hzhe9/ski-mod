package com.hzhe9.skis.client;

import java.lang.reflect.Method;

/**
 * <b>越肩视角（Shoulder Surfing）兼容探测</b>：只读对方模组的公开 API
 * {@code com.github.exopandora.shouldersurfing.api.client.IShoulderSurfing}
 * 的 {@code getInstance()} / {@code isShoulderSurfing()}。
 *
 * <p><b>为什么用反射</b>：那个模组不是本工程的编译期依赖（jar 是玩家自己丢进 mods 的，
 * 文件名还带中文方括号），任何 {@code compileOnly} 都会让别人的构建挂掉。反射只按<b>字符串</b>去找，
 * 对方不在（或版本换了包名）时探测一抛异常就把结果钉成 {@code false}，本模组行为与没接兼容时<b>完全一致</b>。
 *
 * <p><b>缓存策略</b>：类 / 方法句柄只在第一次问的时候解析一次（{@link #probe()}，解析失败就永久 false，
 * 不再重复 {@code Class.forName} 的类加载开销）；但 {@link #isActive()} 每次都<b>重新调用</b>
 * {@code isShoulderSurfing()} —— 那是玩家随时会按切换键开关的活状态，不能缓存成固定值，
 * 否则退出越肩后视角限位再也回不来。调用期若抛异常（对方内部崩了）也钉成 false，避免每帧抛栈卡顿。
 */
public final class ShoulderSurfingCompat {
	/** 对方 API 的接口名（与 jar 里 {@code javap} 出来的全名逐字一致）。 */
	private static final String API_CLASS_NAME = "com.github.exopandora.shouldersurfing.api.client.IShoulderSurfing";

	/** 类 / 方法句柄是否已经问过（无论成功失败，只问一次）。 */
	private static boolean probed;
	/** 对方的单例（{@code getInstance()} 的返回值）。 */
	private static Object instance;
	/** {@code IShoulderSurfing#isShoulderSurfing()}。 */
	private static Method isShoulderSurfingMethod;
	/** 调用期崩过一次：之后一律按「没开」处理，不再反复抛异常。 */
	private static boolean broken;

	private ShoulderSurfingCompat() {
	}

	/**
	 * 越肩视角此刻是否开启。<b>每帧 / 每 tick 都可以问</b>（一次反射调用，句柄已缓存）。
	 *
	 * @return {@code true} = 对方模组在、且玩家正处在越肩视角；其余一切情况（含没装）都是 {@code false}
	 */
	public static boolean isActive() {
		if (!probed) {
			probe();
		}
		if (isShoulderSurfingMethod == null || instance == null || broken) {
			return false;
		}
		try {
			return Boolean.TRUE.equals(isShoulderSurfingMethod.invoke(instance));
		} catch (Throwable t) {
			// 对方内部状态坏了：钉成 false（宁可不兼容，也不要每帧抛一次反射异常）
			broken = true;
			return false;
		}
	}

	/** 解析一次类与方法句柄；任何一步抛异常都按「没有这个模组」处理。 */
	private static synchronized void probe() {
		if (probed) {
			return;
		}
		probed = true;
		try {
			Class<?> api = Class.forName(API_CLASS_NAME);
			Method getInstance = api.getMethod("getInstance");
			Method isShoulderSurfing = api.getMethod("isShoulderSurfing");
			Object singleton = getInstance.invoke(null);
			if (singleton == null) {
				return;
			}
			instance = singleton;
			isShoulderSurfingMethod = isShoulderSurfing;
		} catch (Throwable t) {
			instance = null;
			isShoulderSurfingMethod = null;
		}
	}
}
