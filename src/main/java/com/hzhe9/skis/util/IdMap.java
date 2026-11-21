package com.hzhe9.skis.util;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BinaryOperator;

/**
 * 「实体 id → 值」的缓存表：双板 / 单板的朝向与姿态、滑行动量、雪粒子与滑行声的上一 tick 状态都用它。
 *
 * <p>这些表记的都是<b>跨 tick 的增量状态</b>（上一 tick 的高度、动量、速度、冷却），不是持久数据：
 * 实体离开、id 被回收之后，那份记录就只是垃圾。所以表有上限 {@link #DEFAULT_CAPACITY} 条，
 * <b>超了就按写入顺序淘汰最旧的一条</b>（不是整表清空）。
 *
 * <p><b>为什么不能整表清空</b>：那是悬崖式降级 —— 一旦同时在写表的实体超过上限，每 tick 都会清一次表，
 * 于是所有人的动量归零、{@code BODY_YAW} 被 {@code ensureLocked} 重锁到当前视角（carve 直接失效）、
 * 空中状态 / 失控计时一起丢。淘汰最旧的一条只让<b>那一个</b>实体这一 tick 少一份增量，
 * 其余人完全不受影响；还在滑行的玩家每 tick 都在重写自己的 id，最早被淘汰的永远是已经停下来的那些。
 *
 * <p>容量取 64：远大于同屏滑行的玩家数，同时把「id 只增不减」的泄漏兜住。这是缓存卫生，不是手感旋钮，
 * 所以不进 {@code docs/tuning.md}。
 *
 * <p>写入一并做容量检查（{@link #put} / {@link #putIfAbsent} / {@link #merge}），调用方不需要再自己数条数、
 * 自己清表。底层仍是 {@link ConcurrentHashMap}：服务端 tick 与客户端渲染线程都会读这些表；
 * 淘汰顺序另记一条 {@link ConcurrentLinkedDeque}（近似插入序：并发下最坏只是表比上限多几条待淘汰项，
 * 下一次写入接着淘汰 —— 不会回到整表清空）。
 */
public final class IdMap<T> {
	/** 表的条数上限：超过就淘汰最旧的一条（见类注释）。 */
	public static final int DEFAULT_CAPACITY = 64;

	private final Map<Integer, T> entries = new ConcurrentHashMap<>();
	/**
	 * 插入序（队头最旧）。只在「这个 id 第一次写进来」时入队 —— 每 tick 都重写的活动实体不会把队列撑大，
	 * 淘汰时先动的永远是早就没人写的 id。
	 */
	private final Deque<Integer> insertionOrder = new ConcurrentLinkedDeque<>();

	public T put(int id, T value) {
		T previous = entries.put(id, value);
		if (previous == null) {
			insertionOrder.addLast(id);
			trim();
		}
		return previous;
	}

	public T putIfAbsent(int id, T value) {
		T previous = entries.putIfAbsent(id, value);
		if (previous == null) {
			insertionOrder.addLast(id);
			trim();
		}
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
		boolean absent = !entries.containsKey(id);
		T merged = entries.merge(id, value, remapping);
		if (absent && merged != null) {
			insertionOrder.addLast(id);
			trim();
		}
		return merged;
	}

	/** 逐条访问（按 id 推进的计时表用：{@code SkiState} 每 tick 减一格）。 */
	public void forEach(BiConsumer<Integer, T> action) {
		entries.forEach(action);
	}

	/** 整表清空：只给「换世界 / 重连」这类<b>状态整体作废</b>的场合用，不要拿它当容量兜底。 */
	public void clear() {
		entries.clear();
		insertionOrder.clear();
	}

	private void trim() {
		while (entries.size() > DEFAULT_CAPACITY) {
			Integer oldest = insertionOrder.pollFirst();
			if (oldest == null) {
				return;   // 队列已空（并发下才会）：剩下的都是刚写进来的活动 id，留着比清空好
			}
			entries.remove(oldest);
		}
	}
}
