package cn.studykid.rag.infrastructure.persistence;

import cn.studykid.rag.core.scope.RlsDependencyProbe;
import cn.studykid.rag.core.scope.RlsDependencyProbe.Batch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * rlsDependent 度量守卫（M4 准备期埋点的强制闸门）。
 *
 * <p>静态盘点 rag 主代码中「可执行」的 {@code set_config('app.tenant_id', ...)} 站点，
 * 按 R1/R2/R3/CENTRAL 分批与 {@link RlsDependencyProbe#EXPECTED} 对照。
 * 任何<b>新增或遗漏</b>（即 rlsDependent 漂移）都会让本测试转红——
 * 这正是「rlsDependent 随 M4 逐批退役递减至 0」的强制依据。
 *
 * <p>口径与 {@code RlsDependencyProbe} 一致：仅统计主代码里真正执行
 * {@code queryForObject("SELECT set_config(...)")} 的调用；javadoc/注释、测试夹具、
 * 守卫白名单字符串均不计。
 */
class RlsDependencyMetricTest {

    private static final Pattern SET_CONFIG = Pattern.compile("set_config\\s*\\(\\s*'app\\.tenant_id'");
    private static final Pattern QUERY_FOR_OBJECT = Pattern.compile("queryForObject");

    @Test
    void rlsDependentSitesMatchExpectedByBatch() throws IOException {
        Map<Batch, Integer> actual = new EnumMap<>(Batch.class);
        for (Batch b : Batch.values()) {
            actual.put(b, 0);
        }
        List<String> unknownFilesWithSetConfig = new ArrayList<>();

        Path ragRoot = locateRagRoot();
        assertNotNull(ragRoot, "找不到 rag 源码根（rag-core 所在目录）");

        List<Path> mainFiles = Files.walk(ragRoot)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> p.toString().contains("/src/main/"))
                .collect(Collectors.toList());

        for (Path file : mainFiles) {
            int n = countExecutableSetConfig(file);
            if (n == 0) {
                continue;
            }
            Batch batch = batchOf(file);
            if (batch == null) {
                unknownFilesWithSetConfig.add(file.toString() + " (sites=" + n + ")");
                continue;
            }
            actual.put(batch, actual.get(batch) + n);
        }

        StringBuilder detail = new StringBuilder("rlsDependent 实际盘点: ");
        int totalActual = 0;
        for (Batch b : Batch.values()) {
            int exp = RlsDependencyProbe.EXPECTED.get(b);
            int act = actual.get(b);
            totalActual += act;
            detail.append(b).append("=").append(act).append("(exp ").append(exp).append(") ");
            assertEquals(exp, act, "批次 " + b + " 的 set_config 站点数与预期不符（检查是否新增/遗漏）");
        }
        assertTrue(unknownFilesWithSetConfig.isEmpty(),
                "存在未登记批次的 set_config 站点（须补进 Batch 映射）：" + unknownFilesWithSetConfig);
        assertEquals(RlsDependencyProbe.expectedTotal(), totalActual, "rlsDependent 总数与预期不符");
        assertTrue(totalActual > 0, "rlsDependent 不应为 0（M4 逐批退役后才归 0，归 0 即 M5 出口）");
        System.out.println(detail);
    }

    private static Path locateRagRoot() {
        for (String cand : new String[] { "rag", "../rag", "../../rag" }) {
            Path p = Paths.get(cand);
            if (Files.exists(p.resolve("rag-core"))) {
                return p;
            }
        }
        return null;
    }

    private static Batch batchOf(Path file) {
        String name = file.getFileName().toString();
        return switch (name) {
            case "JdbcCorpusRepository.java", "JdbcTenantScopedRetrieval.java" -> Batch.R1;
            case "JdbcCorpusManagementRepository.java", "JdbcDictionaryRepository.java",
                    "JdbcKnowledgeAliasRepository.java" -> Batch.R2;
            case "ChunkIndexer.java", "IndexGenerationBuilder.java", "JdbcIndexGenerationRepository.java",
                    "DocumentPreviewService.java", "JdbcIngestJobRepository.java", "JdbcIngestSourceLoader.java",
                    "JdbcTenantScopedGeneration.java" -> Batch.R3;
            case "RagSessionScope.java" -> Batch.CENTRAL;
            default -> null;
        };
    }

    private static int countExecutableSetConfig(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        int count = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (!SET_CONFIG.matcher(lines.get(i)).find()) {
                continue;
            }
            String s = lines.get(i).strip();
            if (s.startsWith("*") || s.startsWith("//") || s.startsWith("/*")) {
                continue;
            }
            boolean inCall = false;
            for (int j = Math.max(0, i - 3); j <= i; j++) {
                if (QUERY_FOR_OBJECT.matcher(lines.get(j)).find()) {
                    inCall = true;
                    break;
                }
            }
            if (inCall) {
                count++;
            }
        }
        return count;
    }
}
