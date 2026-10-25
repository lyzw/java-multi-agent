package cn.studykid.multiagent.application.cap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.multiagent.core.cap.CapRoutingPolicyDef;
import cn.studykid.multiagent.core.orchestration.RoutingStrategy;

/**
 * 路由策略内容指纹的证据（多级 Agent v1.1 · 第十四批 · MA-T15）。
 *
 * <p><b>⚠️ 为什么路由策略的内容指纹必须由服务端算</b>：
 * {@code config_fingerprint} 是「这一行内容没变」的证明。
 * 若让调用方（HTTP 客户端或内部调用点）提供这个值，
 * 它就成了一个<b>可以随便填的字符串</b> —— 客户端填 A、实际内容是 B，
 * 闭包指纹仍会自洽，于是「内容变了但指纹没变」这件事就再也检测不出来了。
 * 而路由策略恰恰是「路由行为是什么」的判定依据。
 *
 * <p><b>⚠️ 「全都拒」不能算通过</b>：本类同时钉住「内容变了 → 指纹必须变」
 * 与「内容没变 → 指纹必须不变」两侧，只有一侧的话用例形同虚设。
 */
class CapRoutingPolicyFingerprintTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    private CapRoutingPolicyDef policy(String policyId, int version, RoutingStrategy strategy,
                                       List<String> targets, Instant publishedAt) {
        return new CapRoutingPolicyDef("t-1", policyId, version, strategy, targets,
                "placeholder", 1, NOW, publishedAt);
    }

    @Test
    @DisplayName("内容相同 → 指纹相同（指纹必须稳定，否则每次发布都被读成内容变化）")
    void sameContentSameFingerprint() {
        String a = CapObjectFingerprint.of(
                policy("orders-router", 1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("agent-a", "agent-b"), NOW));
        String b = CapObjectFingerprint.of(
                policy("orders-router", 1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("agent-a", "agent-b"), NOW));
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("发布时间不参与指纹（草稿与发布态是同一份内容）")
    void publishedAtDoesNotAffectFingerprint() {
        String draft = CapObjectFingerprint.of(
                policy("orders-router", 1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("agent-a"), null));
        String published = CapObjectFingerprint.of(
                policy("orders-router", 1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("agent-a"), NOW));
        assertThat(draft)
                .as("时间戳不参与计算，否则每次发布都会让指纹变化")
                .isEqualTo(published);
    }

    @Test
    @DisplayName("目标顺序变化 → 指纹变化（ORDERED_FALLBACK 的顺序就是路由语义）")
    void targetOrderAffectsFingerprint() {
        String first = CapObjectFingerprint.of(
                policy("orders-router", 1, RoutingStrategy.ORDERED_FALLBACK,
                        List.of("agent-a", "agent-b"), NOW));
        String swapped = CapObjectFingerprint.of(
                policy("orders-router", 1, RoutingStrategy.ORDERED_FALLBACK,
                        List.of("agent-b", "agent-a"), NOW));
        assertThat(swapped)
                .as("同一组目标换个次序就是另一种路由行为，指纹必须能区分")
                .isNotEqualTo(first);
    }

    @Test
    @DisplayName("选择策略变化 → 指纹变化（显式集合与有序降级是两种行为）")
    void strategyAffectsFingerprint() {
        String explicit = CapObjectFingerprint.of(
                policy("orders-router", 1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("agent-a"), NOW));
        String fallback = CapObjectFingerprint.of(
                policy("orders-router", 1, RoutingStrategy.ORDERED_FALLBACK,
                        List.of("agent-a"), NOW));
        assertThat(fallback).isNotEqualTo(explicit);
    }

    @Test
    @DisplayName("目标集合变化 → 指纹变化（策略内容变了必须能被检出）")
    void targetsAffectFingerprint() {
        String base = CapObjectFingerprint.of(
                policy("orders-router", 1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("agent-a"), NOW));
        String widened = CapObjectFingerprint.of(
                policy("orders-router", 1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("agent-a", "agent-b"), NOW));
        assertThat(widened).isNotEqualTo(base);
    }

    @Test
    @DisplayName("策略标识与版本变化 → 指纹变化")
    void identityAffectsFingerprint() {
        String base = CapObjectFingerprint.of(
                policy("orders-router", 1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("agent-a"), NOW));
        String otherId = CapObjectFingerprint.of(
                policy("refund-router", 1, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("agent-a"), NOW));
        String otherVersion = CapObjectFingerprint.of(
                policy("orders-router", 2, RoutingStrategy.EXPLICIT_TARGETS,
                        List.of("agent-a"), NOW));
        assertThat(otherId).isNotEqualTo(base);
        assertThat(otherVersion).isNotEqualTo(base);
    }
}
