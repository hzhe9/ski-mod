package io.github.wf.skis.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;

/**
 * 「实体 id → 值」的缓存表：双板 / 单板的朝向与姿态、滑行动量、雪粒子与滑行声的上一 tick 状态都用它。
 *
 * <p>这些表记的都是<b>跨 tick 的增量状态</b>（上一 tick 的高度、动量、速度、冷却），不是持久数据：
 * 实体离开、id 被回收之后，那份记录就只是垃圾。所以表不吃内存上限，超过 {@link #DEFAULT_CAPACITY}
 * 条就<b>整表清空</b> —— 清空最多让下一 tick 少一份增量（当作「刚开始滑」重新起算），滑行与音效
 * 下一 tick 自然重建，不会卡住任何状态。
 *
 * <p>容量取 64：远大于同屏滑行的玩家数，同时把「id 只增不减」的泄漏兜住。这是缓存卫生，不是手感旋钮，
 * 所以不进 {@code docs/tuning.md}。
 *
 * <p>写入一并做容量检查（{@link #put} / {@link #putIfAbsent}），调用方不需要再自己数条数、自己清表。
 * 底层仍是 {@link ConcurrentHashMap}：服务端 tick 与客户端渲染线程都会读这些表。
 */
public final class IdMap<T> {
	/** 表的条数上限：超过就整表清空（见类注释）。 */
	public static final int DEFAULT_CAPACITY = 64;

	private final Map<Integer, T> entries = new ConcurrentHashMap<>();

	public T put(int id, T value) {
		T previous = entries.put(id, value);
		trim();
		return previous;
	}

	public T putIfAbsent(int id, T value) {
		T previous = entries.putIfAbsent(id, value);
		trim();
		return previous;
	}

	public T get(int id) {
		return entries.get(id);
	}

	public T getOrDefault(int id, T fallback) {
		return entries.getOrDefault(id, fallback);
	}

	public T remove(int id) {
		return entries.remove(id);
	}

	public T computeIfPresent(int id, BiFunction<Integer, T, T> remapping) {
		return entries.computeIfPresent(id, remapping);
	}

	public T merge(int id, T value, BinaryOperator<T> remapping) {
		return entries.merge(id, value, remapping);
	}

	public void clear() {
		entries.clear();
	}

	public int size() {
		return entries.size();
	}

	private void trim() {
		if (entries.size() > DEFAULT_CAPACITY) {
			entries.clear();
		}
	}
}
