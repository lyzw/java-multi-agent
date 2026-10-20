package cn.studykid.multiagent.ports.persistence;

import java.util.List;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 一页结果（P4-D13）。
 *
 * <p>⚠️ {@code nextCursor} 为 {@code null} 表示<b>没有下一页</b>——
 * 而「空页」与「最后一页」是两回事：前者是查询出错，后者是查完了。
 * 把两者混同会让前端在空结果时反复翻页（拿到的永远是空）。
 *
 * @param items     本页条目；<b>不可为 null</b>（空即无数据）
 * @param nextCursor 下一页游标；无更多时为 {@code null}
 */
public record Page<T>(@NonNull List<T> items, @Nullable String nextCursor) {

    public Page {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public static <T> @NonNull Page<T> of(@NonNull List<T> items, @Nullable String nextCursor) {
        return new Page<>(items, nextCursor);
    }

    public boolean hasMore() {
        return nextCursor != null;
    }
}