package cn.studykid.multiagent.core.security;

import java.util.Map;

import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * 资源引用 —— 鉴权请求的目标。
 *
 * <p>单资源场景（详情/更新/删除）带 {@code id}；集合场景（列表/检索）{@code id} 为 {@code null}，
 * 由 {@code type} 标识资源类（如 {@code rag_chunk} / {@code cap_agent_def}）。
 */
public record ResourceRef(
        @NonNull String type,
        @Nullable String id,
        @NonNull Map<String, Object> attrs) {

    public ResourceRef {
        attrs = attrs == null ? Map.of() : Map.copyOf(attrs);
    }

    /** 集合场景工厂：仅按类型鉴权，不带单资源 id。 */
    public static @NonNull ResourceRef collection(@NonNull String type) {
        return new ResourceRef(type, null, Map.of());
    }

    /** 集合场景工厂（带附加属性，如 verStatus / trustTier）。 */
    public static @NonNull ResourceRef collection(@NonNull String type, @NonNull Map<String, Object> attrs) {
        return new ResourceRef(type, null, attrs);
    }
}
