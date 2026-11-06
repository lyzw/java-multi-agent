package cn.studykid.rag.infrastructure.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import cn.studykid.rag.infrastructure.ops.RagOpsSessionPort;

/**
 * {@link JdbcIngestJobRepository} 的**留痕回写**参数绑定回归测试。
 *
 * <p><b>为什么这条测试存在</b>（2026-11-06 灰度实测暴露）：
 * {@code markSucceeded} / {@code markSucceededGlobal} 用「动态 SET」拼 SQL
 * （{@code chunk_policy} / {@code chunk_policy_config} 为 null 时不写该列），
 * 而 {@code job_id} 的占位符在 <b>WHERE 段</b>、参数却按 <b>SET 段</b>顺序追加
 * —— 于是 {@code jobId} 必须<b>最后</b>加。漏加时：
 * <ul>
 *   <li>占位符 6 个、参数只给 5 个 → 运行期报「未设定参数值 6 的内容」；</li>
 *   <li>且只在 premise 模式（两列都非 null）才暴露 —— 旧签名（两列都为 null）
 *       时占位符 4 个、参数 3 个，同样少一个 → <b>这条回写路径从来没成功过一次</b>；</li>
 *   <li>失败发生在<b>全部片段与向量已落库之后</b> → 表现为「972 个片段在库里、
 *       job 却卡在 FAILED」，排查成本极高。</li>
 * </ul>
 *
 * <p>⚠️ <b>为什么既有的 {@code GlobalIngestRepositoryTest} 抓不到</b>：
 * 它只记录 SQL <b>文本</b>，从不校验<b>参数个数</b>与占位符个数是否一致。
 * 本测试补上这条不变式：**SQL 里 {@code ?} 的个数 == 绑定参数的个数**。
 */
class JdbcIngestJobRepositoryTraceTest {

    /** 一次 update 调用：SQL 文本 + 绑定参数。 */
    private record UpdateCall(String sql, Object[] args) {
    }

    private final List<UpdateCall> calls = new ArrayList<>();

    /** 记录 update 的 SQL 与参数的假 JdbcTemplate。 */
    private JdbcTemplate recordingJdbc() {
        return mock(JdbcTemplate.class, invocation -> {
            String name = invocation.getMethod().getName();
            Object[] a = invocation.getArguments();
            if ("update".equals(name) && a.length >= 1 && a[0] instanceof String sql) {
                // ⚠️ Mockito 的 getArguments() 对可变参数方法会**展开**成
                //    [sql, arg1, arg2, ...]（不是 [sql, Object[]]）——
                //    按 a[1] instanceof Object[] 取会拿到 null，参数个数恒为 0。
                Object[] bound = a.length >= 2
                        ? java.util.Arrays.copyOfRange(a, 1, a.length)
                        : new Object[0];
                calls.add(new UpdateCall(sql, bound));
                return 1;
            }
            if ("query".equals(name)) {
                return "x";
            }
            return null;
        });
    }

    private static TransactionTemplate passthroughTx() {
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        return tx;
    }

    private static final class OpsSession implements RagOpsSessionPort {
        private final JdbcTemplate jdbcTemplate;

        OpsSession(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public <T> T inGlobalCorpusSession(RagOpsSessionPort.RagOpsAction<T> action) {
            return action.run(jdbcTemplate, passthroughTx());
        }
    }

    /** 不变式本身：占位符个数 == 绑定参数个数。 */
    private static void assertPlaceholdersMatchArgs(UpdateCall call) {
        int placeholders = 0;
        for (int i = 0; i < call.sql().length(); i++) {
            if (call.sql().charAt(i) == '?') {
                placeholders++;
            }
        }
        assertEquals(placeholders, call.args().length,
                "占位符个数与绑定参数个数必须一致 —— 不一致时 PG 报「未设定参数值 N 的内容」。\nSQL: "
                        + call.sql());
    }

    @Test
    void tenantMarkSucceededBindsJobIdLast() {
        JdbcIngestJobRepository repo =
                new JdbcIngestJobRepository(recordingJdbc(), passthroughTx());

        repo.markSucceeded("t-1", "job-1", 7, "parser-v1", "model-v1",
                "HEADING_HIERARCHY", "maxTokens=512,overlapRatio=0.15");

        UpdateCall call = calls.get(calls.size() - 1);
        assertPlaceholdersMatchArgs(call);
        assertEquals("job-1", call.args()[call.args().length - 1],
                "job_id 的占位符在 WHERE 段 → 必须绑在最后");
        assertTrue(call.sql().contains("chunk_policy = ?"), "premise 模式必须写留痕列");
        assertTrue(call.sql().contains("chunk_policy_config = ?"), "premise 模式必须写留痕参数");
    }

    @Test
    void tenantMarkSucceededOldSignatureAlsoBindsJobId() {
        JdbcIngestJobRepository repo =
                new JdbcIngestJobRepository(recordingJdbc(), passthroughTx());

        repo.markSucceeded("t-1", "job-1", 7, "parser-v1", "model-v1");

        UpdateCall call = calls.get(calls.size() - 1);
        assertPlaceholdersMatchArgs(call);
        assertEquals("job-1", call.args()[call.args().length - 1]);
    }

    @Test
    void tenantMarkSucceededWithOnlyPolicyBindsOne() {
        JdbcIngestJobRepository repo =
                new JdbcIngestJobRepository(recordingJdbc(), passthroughTx());

        repo.markSucceeded("t-1", "job-1", 7, "parser-v1", "model-v1",
                "HEADING_HIERARCHY", null);

        UpdateCall call = calls.get(calls.size() - 1);
        assertPlaceholdersMatchArgs(call);
        assertTrue(call.sql().contains("chunk_policy = ?"));
        assertTrue(!call.sql().contains("chunk_policy_config = ?"),
                "null 参数列不得出现在 SQL 里（「未采集」≠「空串」）");
    }

    @Test
    void globalMarkSucceededBindsJobIdLast() {
        JdbcTemplate jdbc = recordingJdbc();
        JdbcIngestJobRepository repo =
                new JdbcIngestJobRepository(jdbc, passthroughTx(), new OpsSession(jdbc));

        repo.markSucceededGlobal("job-1", 7, "parser-v1", "model-v1",
                "HEADING_HIERARCHY", "maxTokens=512,overlapRatio=0.15");

        UpdateCall call = calls.get(calls.size() - 1);
        assertPlaceholdersMatchArgs(call);
        assertEquals("job-1", call.args()[call.args().length - 1]);
    }

    @Test
    void globalMarkSucceededOldSignatureAlsoBindsJobId() {
        JdbcTemplate jdbc = recordingJdbc();
        JdbcIngestJobRepository repo =
                new JdbcIngestJobRepository(jdbc, passthroughTx(), new OpsSession(jdbc));

        repo.markSucceededGlobal("job-1", 7, "parser-v1", "model-v1");

        UpdateCall call = calls.get(calls.size() - 1);
        assertPlaceholdersMatchArgs(call);
        assertEquals("job-1", call.args()[call.args().length - 1]);
    }
}
