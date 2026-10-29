package cn.studykid.rag.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link RagStartupSelfCheck} 的运维角色互斥判定（RAG-D11 第二刀 §2.2）。
 *
 * <p>只测判定，不连库：{@code rag_ops} 按设计带 BYPASSRLS，
 * 「主数据源误填成运维角色」是第二刀新引入的失效形态，
 * 而它恰好<b>不会</b>被既有的「连接角色非特权」检查以配置视角指出——
 * 后者只能说「连上的这个角色有 BYPASSRLS」，说不出「你把两个角色写成了同一个」。
 */
class RagStartupSelfCheckOpsRoleTest {

    private static RagStartupSelfCheck selfCheck(boolean opsEnabled,
                                                 String opsUsername, String mainUsername) {
        // 判定函数不碰库，JdbcTemplate 只为满足构造。
        return new RagStartupSelfCheck(new JdbcTemplate(), opsEnabled, opsUsername, mainUsername);
    }

    private static List<String> assess(RagStartupSelfCheck check, String currentUser) {
        List<String> failures = new ArrayList<>();
        check.assessOpsRoleSeparation(failures, currentUser);
        return failures;
    }

    @Test
    void unconfiguredOpsRoleIsNotChecked() {
        assertEquals(List.of(), assess(selfCheck(true, "", "rag_app"), "rag_app"),
                "没配运维角色就没有「同一个角色」这回事，不该产生噪声");
    }

    @Test
    void distinctRolesPass() {
        assertEquals(List.of(), assess(selfCheck(true, "rag_ops", "rag_app"), "rag_app"),
                "正常配置必须零失败——误报会让运维把自检关掉");
    }

    /** 配置层形态：两个键写成同一个名字。 */
    @Test
    void sameRoleInBothConfigsIsRefused() {
        List<String> failures = assess(selfCheck(true, "rag_ops", "rag_ops"), "rag_app");

        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.get(0).contains("同一个角色"), failures.get(0));
        assertTrue(failures.get(0).contains("rag.ops.datasource"), failures.get(0));
    }

    /** 运行期形态：配置没写、被环境变量覆盖，实际连上的正是运维角色。 */
    @Test
    void connectedAsOpsRoleIsRefusedEvenWhenConfigsDiffer() {
        List<String> failures = assess(selfCheck(true, "rag_ops", ""), "rag_ops");

        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.get(0).contains("当前数据库连接角色正是运维角色"), failures.get(0));
    }

    /** 角色名大小写不敏感：PG 未加引号的标识符会折叠成小写，比较不能因此漏判。 */
    @Test
    void roleComparisonIsCaseAndSpaceInsensitive() {
        List<String> failures = assess(selfCheck(true, "RAG_OPS", " rag_ops "), "RAG_OPS");

        assertEquals(2, failures.size(), "配置层与运行期各命中一次：" + failures);
    }

    /** 通道显式关闭也要互检：「今天关着」不等于「这个名字填错了无害」。 */
    @Test
    void separationIsCheckedEvenWhenChannelIsDisabled() {
        assertEquals(2, assess(selfCheck(false, "rag_ops", "rag_ops"), "rag_ops").size());
    }

    @Test
    void nullCurrentReadingFallsBackToConfigComparisonOnly() {
        List<String> failures = assess(selfCheck(true, "rag_ops", "rag_ops"), null);

        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.get(0).contains("同一个角色"), failures.get(0));
    }
}
