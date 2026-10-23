package cn.studykid.multiagent.testsupport.acceptance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 冻结门槛证据的<b>机器校验</b>（§8.3 三类门槛 · W-PERM-P6-T4/T6）。
 *
 * <p>⚠️ <b>本类把「门槛已达成」从一句话变成一组断言</b>：
 * 每一项门槛都指名了若干测试方法；本类回到源码里核对
 * <b>每个方法都确实存在</b>。
 *
 * <p>⚠️ <b>为什么这一层不可省</b>：
 * 冻结报告里写「防绕过 0 通过」「RLS 隔离通过」时，
 * 读者无法判断它指的是哪条用例；一旦那条用例被改名，
 * 报告就变成一句无人能验证的话。
 * 指名 + 机器核对，是把门槛结论<b>锚定在代码上</b>的最低成本做法。
 *
 * <p>⚠️ <b>本类只证明「证据还在」，不证明「证据为绿」</b>：
 * 「为绿」由全量构建（surefire + failsafe）承担。
 * 两者合起来才是完整结论：**证据存在 ∧ 执行通过**。
 */
class FreezeGateEvidenceTest {

    @Test
    @DisplayName("✅ 三类门槛 + 硬约束全部登记，且每项都有证据落点")
    void everyGateItemHasEvidence() {
        List<String> problems = new ArrayList<>();

        Set<String> groupIds = new LinkedHashSet<>();
        for (FreezeGateEvidenceRegistry.GateGroup group : FreezeGateEvidenceRegistry.groups()) {
            if (!groupIds.add(group.id())) {
                problems.add("门槛分组重复: " + group.id());
            }
            if (group.items().isEmpty()) {
                problems.add("门槛分组为空: " + group.id());
            }
            for (FreezeGateEvidenceRegistry.GateItem item : group.items()) {
                if (item.evidenceRefs().isEmpty()) {
                    problems.add(item.id() + " 没有任何证据落点");
                }
                if (item.note() == null || item.note().isBlank()) {
                    problems.add(item.id() + " 缺备注");
                }
            }
        }

        assertThat(groupIds)
                .as("⛔ §8.3 的三类门槛 + 硬约束必须全部登记：功能 / 安全 / 工程 / Demo≠验收")
                .containsExactlyInAnyOrder("FUNC", "SEC", "ENG", "HARD");
        assertThat(problems).as("⛔ 门槛登记自检未通过").isEmpty();
    }

    @Test
    @DisplayName("✅ 每个门槛证据的方法在源码中确实存在")
    void everyGateEvidenceMethodExists() {
        List<String> missing = new ArrayList<>();

        for (FreezeGateEvidenceRegistry.GateItem item : FreezeGateEvidenceRegistry.allItems()) {
            for (String ref : item.evidenceRefs()) {
                int hash = ref.indexOf('#');
                String className = ref.substring(0, hash);
                String methodName = ref.substring(hash + 1);
                Optional<String> body = TestSourceIndex.methodBody(className, methodName);
                if (body.isEmpty()) {
                    missing.add(item.id() + " → " + ref);
                }
            }
        }

        assertThat(missing)
                .as("⛔ 门槛证据路径失效：登记的测试方法在源码中不存在。"
                        + "冻结结论不能建立在一条已经消失的用例上")
                .isEmpty();
    }

    @Test
    @DisplayName("✅ 「防绕过 0」三层证据齐备（静态扫描 + 架构边界 + 运行时注入）")
    void antiBypassHasAllThreeLayers() {
        Set<String> covered = new LinkedHashSet<>();
        for (FreezeGateEvidenceRegistry.GateItem item : FreezeGateEvidenceRegistry.allItems()) {
            if (!item.id().startsWith("SEC-")) {
                continue;
            }
            if (item.title().contains("防绕过")) {
                covered.add(item.id());
            }
        }

        assertThat(covered)
                .as("⛔ 07 §3 明确：静态扫描无法单独证明闭环，"
                        + "防绕过必须由「代码扫描 + 架构测试 + 运行时测试」三者共同验证")
                .containsExactlyInAnyOrder("SEC-1", "SEC-2", "SEC-3", "SEC-4");
    }
}
