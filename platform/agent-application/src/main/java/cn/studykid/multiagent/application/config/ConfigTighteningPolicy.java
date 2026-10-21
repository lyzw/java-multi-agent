package cn.studykid.multiagent.application.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.config.ApprovalRequirement;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort;
import cn.studykid.multiagent.ports.persistence.ConfigOverridePort.Tightening;

/**
 * 配置收紧策略（P4-D15 / F6 · **C5** 的运行时强制）。
 *
 * <p><b>⚠️ 本类关闭的是什么缺口</b>：FE-02 §0.4 记载
 * 「审批策略只能收紧不能放松 —— 🟡 <b>算法已实现但未接入运行时</b>」。
 * 实测更正：算法（{@link ApprovalRequirement#isRelaxationTo} / {@code maxWith}）
 * 早已在 3 处生产接线；但<b>「谁能写配置」这条路径完全不存在</b>，
 * 故收紧语义<b>从未在写入侧被强制过</b>。本类就是那个写入侧闸门。
 *
 * <p><b>⚠️ 为什么「写入时拒绝」而非「静默取更严者」</b>：
 * {@code maxWith} 的语义是「配置试图放松 → 取更严格者」，
 * 适合<b>读取时合并多个来源</b>。但若写入时静默取严，
 * 管理员会看到「保存成功」而实际值未变 —— 这正是 FE-02 §4
 * 「误导性文案比功能缺失更有害」所警告的形态。
 * 故<b>写入时明确抛异常</b>，让操作者知道「这条被拒了」。
 *
 * <p><b>⚠️ 单向棘轮不是「一次性」约束</b>：本类比较的是
 * <b>当前生效值</b>（DB 覆盖 ⊕ yml 默认），而非 yml 默认。
 * 若拿 yml 默认做基准，管理员就能通过「先收紧再反复重写」绕过——
 * 收紧一次后，后续所有值都要 ≥ 那次收紧后的值，而不是 ≥ yml 默认。
 */
public final class ConfigTighteningPolicy {

    /** 变更理由最少字数（C7）。 */
    public static final int MIN_REASON_LENGTH = 10;

    private ConfigTighteningPolicy() {
    }

    /**
     * 校验一次写入请求。
     *
     * @param key           配置键
     * @param newValue      新值文本
     * @param currentValue  <b>当前生效值</b>（可为 null = 尚无覆盖，用 yml 默认）
     * @param reason        CHG 变更理由
     * @param valueType     值的类型（{@link ValueType}）
     * @throws ConfigRelaxationException  试图放松保护强度 → 端点转 409
     * @throws IllegalArgumentException  键不可写 / 格式非法 / 理由过短 → 端点转 400
     */
    public static void validate(@NonNull String key,
                                @NonNull String newValue,
                                String currentValue,
                                @NonNull String reason,
                                @NonNull ValueType valueType) {
        Tightening direction = ConfigOverridePort.WRITABLE.get(key);
        if (direction == null) {
            // 键不在白名单 → 不可写。⚠️ 刻意用 400 而非 404：
            // 「不可写」是本体系的既定边界，不是「资源不存在」。
            throw new IllegalArgumentException(
                    "配置键不可写（不在白名单内）: " + key);
        }
        if (reason == null || reason.trim().length() < MIN_REASON_LENGTH) {
            throw new IllegalArgumentException(
                    "变更理由不得少于 " + MIN_REASON_LENGTH + " 字（CHG 要求）");
        }

        switch (direction) {
            case TIGHTEN -> checkUpperBoundTightening(key, newValue, currentValue, valueType);
            case TIGHTEN_RATCHET -> checkRatchetTightening(key, newValue, currentValue);
            case NEUTRAL -> valueType.parse(newValue); // 仅校验格式，无方向约束
        }
    }

    /**
     * 上限类：只能变小。
     *
     * <p>⚠️ 保留期 / TTL 的方向与上限类<b>相反</b>：它们是「只能变短」，
     * 但语义同样是「更严」。这里按各自的自然序比较——
     * 上限类变小为收紧，保留期变小也为收紧（留存更短 = 更合规）。
     */
    private static void checkUpperBoundTightening(String key,
                                                  String newValue,
                                                  String currentValue,
                                                  ValueType type) {
        BigDecimal next = type.parse(newValue);
        if (currentValue == null) {
            return; // 无当前值 = 从 yml 默认起步，此时任何正值都合法（格式已校验）
        }
        BigDecimal current = type.parse(currentValue);
        if (next.compareTo(current) > 0) {
            throw new ConfigRelaxationException(key, currentValue, newValue,
                    "上限类配置只能收紧（变小），当前 " + currentValue + " → 试图写入 " + newValue);
        }
    }

    /**
     * 审批要求矩阵：按风险等级只能收紧。
     *
     * <p>⚠️ 值形如 {@code "READ=REQUIRED,WRITE_EXTERNAL=REQUIRED"}。
     * 校验规则：<b>每个等级都不得比当前更松</b>（{@code isRelaxationTo}）。
     * 只写部分等级也允许——未出现的等级保持现状。
     */
    private static void checkRatchetTightening(String key,
                                               String newValue,
                                               String currentValue) {
        Map<ToolRiskKey, ApprovalRequirement> next = parseMatrix(newValue);
        if (next.isEmpty()) {
            throw new IllegalArgumentException("审批矩阵格式非法: " + newValue);
        }
        if (currentValue == null) {
            return;
        }
        Map<ToolRiskKey, ApprovalRequirement> current = parseMatrix(currentValue);
        for (Map.Entry<ToolRiskKey, ApprovalRequirement> e : next.entrySet()) {
            ApprovalRequirement now = current.get(e.getKey());
            if (now != null && now.isRelaxationTo(e.getValue())) {
                throw new ConfigRelaxationException(key, currentValue, newValue,
                        "审批要求只能收紧：" + e.getKey() + " 当前 " + now
                                + " → 试图写入更宽松的 " + e.getValue());
            }
        }
    }

    /** 解析 {@code "READ=REQUIRED,WRITE_EXTERNAL=REQUIRED"}。 */
    private static Map<ToolRiskKey, ApprovalRequirement> parseMatrix(String raw) {
        Map<ToolRiskKey, ApprovalRequirement> out = new LinkedHashMap<>();
        // ⚠️ **必须先剥掉 yml map 的花括号**：
        //    当前生效值来自 ConfigReadService.declaredValueOf()，
        //    它对嵌套 map 返回 Java 的 Map.toString()，形如
        //    `{READ=REQUIRED, WRITE_EXTERNAL=REQUIRED}`（注意大括号与逗号后空格）。
        //    实测：不剥括号时第一段被解析成 "{READ" → 抛「风险等级非法」。
        //    这不是测试写法问题，而是生产读路径的真实形态。
        String s0 = raw.trim();
        if (s0.startsWith("{") && s0.endsWith("}")) {
            s0 = s0.substring(1, s0.length() - 1);
        }
        for (String part : s0.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            int eq = s.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            ToolRiskKey level = ToolRiskKey.parse(s.substring(0, eq).trim());
            ApprovalRequirement req;
            try {
                req = ApprovalRequirement.valueOf(s.substring(eq + 1).trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("审批要求取值非法: " + s);
            }
            out.put(level, req);
        }
        return out;
    }

    /** 风险等级键（与 {@code ToolRiskLevel} 对齐）。 */
    public enum ToolRiskKey {
        READ, WRITE_PROFILE, WRITE_EXTERNAL;

        static ToolRiskKey parse(String s) {
            try {
                return valueOf(s.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("风险等级非法: " + s);
            }
        }
    }

    /** 配置值的类型。 */
    public enum ValueType {
        INTEGER {
            @Override
            public BigDecimal parse(String raw) {
                try {
                    return new BigDecimal(raw.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("应为整数: " + raw);
                }
            }
        },
        DECIMAL {
            @Override
            public BigDecimal parse(String raw) {
                try {
                    return new BigDecimal(raw.trim());
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("应为数值: " + raw);
                }
            }
        },
        DURATION {
            @Override
            public BigDecimal parse(String raw) {
                Duration d = parseDuration(raw);
                return BigDecimal.valueOf(d.toMinutes());
            }
        },
        ENUM {
            @Override
            public BigDecimal parse(String raw) {
                // 枚举不做数值解析，仅校验非空；方向约束由各自的 policy 负责
                if (raw == null || raw.isBlank()) {
                    throw new IllegalArgumentException("枚举值不得为空");
                }
                return BigDecimal.ZERO;
            }
        };

        public abstract BigDecimal parse(@NonNull String raw);

        /** 解析 yml 时长写法（{@code 15m} / {@code 2h} / {@code 30s} / 纯数字按分钟）。 */
        static Duration parseDuration(String raw) {
            String s = raw.trim().toLowerCase();
            try {
                if (s.endsWith("ms")) {
                    return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2)));
                }
                if (s.endsWith("s")) {
                    return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
                }
                if (s.endsWith("m")) {
                    return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
                }
                if (s.endsWith("h")) {
                    return Duration.ofHours(Long.parseLong(s.substring(0, s.length() - 1)));
                }
                return Duration.ofMinutes(Long.parseLong(s));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("时长格式非法: " + raw);
            }
        }
    }

    /** 试图放松保护强度（→ 409）。 */
    public static class ConfigRelaxationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private final String key;

        public ConfigRelaxationException(String key, String currentValue,
                                        String attemptedValue, String reason) {
            super("配置变更被拒：" + reason);
            this.key = key;
        }

        public String key() {
            return key;
        }
    }
}