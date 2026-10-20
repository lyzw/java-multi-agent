package cn.studykid.multiagent.api.config;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

import cn.studykid.multiagent.core.compliance.ZeroRetentionGate;
import cn.studykid.multiagent.core.compliance.ZeroRetentionStatus;

/**
 * 配置只读投影服务（P4-D07 / C1）。
 *
 * <p><b>本类是什么</b>：一个<b>纯只读</b>的「配置投影」——从 {@link Environment}
 * 读出 yml 声明值，并据已知接线事实标注每条配置的 {@code effective}（是否真正接入运行时）。
 * <b>不含任何业务逻辑</b>，故放在 api 层作为读模型，不污染 application 域服务。
 *
 * <p><b>⚠️ 诚实性铁律（FE-02 §0.3）</b>：
 * 本项目存在大量「声明了但生产无读取点」的 yml 键（C8 缺口）。
 * 本服务<b>绝不</b>把声明值当作生效值——{@code effective=false} 的条目
 * 明确标注「改后不影响行为」。这是配置页诚实呈现「声明 vs 实际」的前提。
 *
 * <p><b>⚠️ 安全铁律（P0-D03 §4 红线 15）</b>：
 * 响应<b>绝不</b>包含任何密钥 / 口令 / 私钥。
 * {@code auth.jwt.private-key}、{@code RABBITMQ_PASSWORD} 等敏感项
 * 不在任何字段中出现；仅 {@code auth.jwt.token-ttl-seconds}（令牌有效期，
 * 非敏感）被纳入并标注为已接入。
 */
public class ConfigReadService {

    private static final String KEY_ZERO_RETENTION_PROVIDERS =
            "agent.compliance.zero-retention.confirmed-providers";

    private final Environment environment;
    private final ZeroRetentionGate zeroRetentionGate;

    public ConfigReadService(Environment environment, ZeroRetentionGate zeroRetentionGate) {
        this.environment = environment;
        this.zeroRetentionGate = zeroRetentionGate;
    }

    /**
     * 配置目录：每条含分组、键、是否接入运行时、来源、诚实说明。
     *
     * <p>⚠️ {@code effective} 取值严格依据 P4-D07 §2.2 表 + P4 已接线事实，
     * 不得凭印象填写。已接入运行时的（true）与仅声明未接线/未确认的（false）分开。
     */
    private static final List<Spec> CATALOG = List.of(
            // ── 运行与容量（已接入运行时）──
            new Spec("运行与容量", "agent.backpressure.enabled", true, "yml",
                    "P4-D05 已实现并接入运行时"),
            new Spec("运行与容量", "agent.backpressure.high-watermark", true, "yml",
                    "P4-D05 已实现"),
            new Spec("运行与容量", "agent.backpressure.low-watermark", true, "yml",
                    "P4-D05 已实现"),
            new Spec("运行与容量", "agent.backpressure.cooldown-multiplier", true, "yml",
                    "P4-D05 已实现"),
            new Spec("运行与容量", "agent.rate-limit.limit-per-window", true, "yml",
                    "P3-D08 已实现"),
            new Spec("运行与容量", "agent.rate-limit.window-seconds", true, "yml",
                    "P3-D08 已实现"),
            new Spec("运行与容量", "agent.worker.enabled", true, "yml",
                    "P4-D03 已实现"),
            new Spec("运行与容量", "agent.worker.relay-interval-ms", true, "yml",
                    "P4-D03 已实现"),
            new Spec("运行与容量", "agent.worker.concurrency", true, "yml",
                    "P4-D03 已实现"),
            new Spec("运行与容量", "agent.worker.prefetch", true, "yml",
                    "P4-D03 已实现"),
            new Spec("运行与容量", "agent.worker.outbox-batch", true, "yml",
                    "P4-D03 已实现"),
            new Spec("运行与容量", "agent.worker.max-publish-attempts", true, "yml",
                    "P4-D03 已实现"),
            new Spec("运行与容量", "deployment.pilot.max-tenants", true, "yml",
                    "P4-D05 校验"),
            new Spec("运行与容量", "agent.budget.reserve-per-tool-call", true, "yml",
                    "P3-D04 装配期校验（fail-closed）"),
            new Spec("运行与容量", "auth.jwt.token-ttl-seconds", true, "yml",
                    "已接入运行时（仅暴露 ttl，不暴露私钥）"),

            // ── 数据保留与删除（PIPL 未成年人，已接入运行时）──
            new Spec("数据保留与删除", "agent.retention.enabled", true, "yml",
                    "P4-D04 已实现"),
            new Spec("数据保留与删除", "agent.retention.profile-months", true, "yml",
                    "P4-D04 已实现"),
            new Spec("数据保留与删除", "agent.retention.prompt-days", true, "yml",
                    "P4-D04 已实现"),
            new Spec("数据保留与删除", "agent.retention.audit-days", true, "yml",
                    "P4-D04 已实现"),
            new Spec("数据保留与删除", "agent.retention.trace-days", true, "yml",
                    "P4-D04 已实现"),
            new Spec("数据保留与删除", "agent.retention.withdrawal-grace-days", true, "yml",
                    "P4-D04 已实现"),
            new Spec("数据保留与删除", "agent.retention.batch-size", true, "yml",
                    "P4-D04 已实现"),
            new Spec("数据保留与删除", "agent.retention.interval-ms", true, "yml",
                    "P4-D04 已实现"),
            new Spec("数据保留与删除", "agent.retention.actor", true, "yml",
                    "P4-D04（非敏感作业名）"),

            // ── 工具与审批（声明未接线）──
            new Spec("工具与审批", "agent.run.max-cost-per-run", false, "yml",
                    "FE-02 §2：生产无读取点，改后不影响行为"),
            new Spec("工具与审批", "agent.run.max-llm-calls", false, "yml",
                    "FE-02 §2：生产无读取点，改后不影响行为"),
            new Spec("工具与审批", "agent.run.max-steps", false, "yml",
                    "FE-02 §2：生产无读取点，改后不影响行为"),
            new Spec("工具与审批", "agent.tenant.max-concurrent-runs", false, "yml",
                    "FE-02 §2：生产无读取点，改后不影响行为"),
            new Spec("工具与审批", "agent.tool.risk-level-map", false, "yml(校验镜像)",
                    "权威来自代码 ToolHandler.riskLevel()，yml 仅校验镜像"),
            new Spec("工具与审批", "agent.approval.required-by-risk", false, "yml",
                    "收紧算法已有但未接线"),
            new Spec("工具与审批", "agent.approval.ttl", false, "yml",
                    "审批有效期声明未接线"),
            new Spec("工具与审批", "agent.approval.approver-scope", false, "yml",
                    "审批人范围声明未接线"),

            // ── 合规与零保留（声明未确认）──
            new Spec("合规与零保留", KEY_ZERO_RETENTION_PROVIDERS, false, "yml",
                    "仅声明未确认证据 → 仍 fail-closed"),
            new Spec("合规与零保留",
                    "agent.compliance.zero-retention.validity-months", false, "yml",
                    "未接入运行时确认逻辑"),
            new Spec("合规与零保留",
                    "agent.compliance.zero-retention.expiry-warn-days", false, "yml",
                    "未接入运行时确认逻辑")
    );

    /** 生成配置快照。 */
    public ConfigResponse snapshot() {
        Instant now = Clock.systemUTC().instant();

        Map<String, List<ConfigEntry>> byGroup = new LinkedHashMap<>();
        Map<String, Boolean> effectiveFlags = new LinkedHashMap<>();
        for (Spec spec : CATALOG) {
            ConfigEntry entry = new ConfigEntry(
                    spec.key, declaredValueOf(spec.key), spec.effective, spec.source, spec.note);
            byGroup.computeIfAbsent(spec.group, k -> new ArrayList<>()).add(entry);
            effectiveFlags.put(spec.key, spec.effective);
        }

        List<ConfigGroup> groups = new ArrayList<>();
        byGroup.forEach((name, entries) -> groups.add(new ConfigGroup(name, entries)));

        return new ConfigResponse(groups, zeroRetentionView(now), effectiveFlags);
    }

    /** 零保留运行态视图。 */
    private ZeroRetentionView zeroRetentionView(Instant now) {
        List<String> declared = new ArrayList<>();
        String raw = environment.getProperty(KEY_ZERO_RETENTION_PROVIDERS);
        if (raw != null && !raw.isBlank()) {
            for (String id : raw.split(",")) {
                String trimmed = id.trim();
                if (!trimmed.isEmpty()) {
                    declared.add(trimmed);
                }
            }
        }

        Map<String, String> status = new LinkedHashMap<>();
        boolean anyConfirmed = false;
        for (String id : declared) {
            ZeroRetentionStatus.State s = zeroRetentionGate.registry().statusOf(id).effectiveState(now);
            status.put(id, s.name());
            if (s == ZeroRetentionStatus.State.CONFIRMED) {
                anyConfirmed = true;
            }
        }

        // GATE-1 是否生效：无任何供应商允许真实数据接入。
        boolean failClosed = !zeroRetentionGate.registry().allAllow(now);

        return new ZeroRetentionView(declared, status, anyConfirmed, failClosed);
    }

    /**
     * 读取声明值。
     *
     * <p>⚠️ 先取标量；标量不存在（如嵌套 map 的父键）再经 {@link Binder}
     * 按 map 解析，以兼容 {@code agent.tool.risk-level-map} 这类 YAML 映射。
     * 两者皆无则标为「(未声明)」。
     */
    private String declaredValueOf(String key) {
        String scalar = environment.getProperty(key);
        if (scalar != null) {
            return scalar;
        }
        Map<String, Object> map = Binder.get(environment)
                .bind(key, Bindable.mapOf(String.class, Object.class))
                .orElse(null);
        if (map != null) {
            return map.toString();
        }
        return "(未声明)";
    }

    /** 目录条目（内部）。 */
    private record Spec(String group, String key, boolean effective, String source, String note) {
    }
}
