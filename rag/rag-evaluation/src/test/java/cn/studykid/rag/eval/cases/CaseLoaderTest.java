package cn.studykid.rag.eval.cases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 评测基础设施骨架验收（RAG-D28 P0-3 / D08 SG-1）。
 *
 * <p>锁定三件事：
 * <ol>
 *   <li>case 文件可加载（FR-RAG-002）；</li>
 *   <li>标注纪律 D-A~E 断言生效（FR-RAG-003/004）；</li>
 *   <li><b>D-F 双向孤儿检测</b>生效——这是本测试的反证核心：
 *       移除一条 acceptance 后必须转红，否则孤儿检测形同虚设。</li>
 * </ol>
 *
 * <p>⚠️ 诚实边界：加载的 case 是<b>合成结构夹具</b>，本测试只证明基础设施可用，
 * 不宣称任何检索/接地质量指标达标（D08 §8.1）。
 */
class CaseLoaderTest {

    /** 定位仓库内的 rag/eval/cases 目录（测试工作目录为模块根）。 */
    private static Path casesDir() {
        Path candidate = Path.of("..", "eval", "cases");
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        // 兼容从仓库根执行（surefire basedir 差异）
        Path alt = Path.of("rag", "eval", "cases");
        if (Files.isDirectory(alt)) {
            return alt;
        }
        throw new IllegalStateException("找不到 case 目录: " + candidate.toAbsolutePath());
    }

    @Test
    @DisplayName("FR-RAG-002：结构夹具 case 集可加载，且版本/changeNote 齐全")
    void loadsFixtureCases() {
        List<CaseSet> sets = CaseLoader.loadAll(casesDir());

        assertThat(sets).isNotEmpty();
        CaseSet fixture = sets.stream()
                .filter(s -> "RAG-FIXTURE-STRUCTURE".equals(s.caseSetId()))
                .findFirst().orElseThrow();
        assertThat(fixture.version()).isNotBlank();
        assertThat(fixture.changeNote()).isNotBlank();
        assertThat(fixture.cases()).hasSize(27);
    }

    @Test
    @DisplayName("D-A~E + D-F：合成结构夹具通过全部标注纪律与双向孤儿检测")
    void fixturePassesAllDiscipline() {
        List<CaseSet> sets = CaseLoader.loadAll(casesDir());

        List<String> violations = CaseDiscipline.validate(sets, CaseDiscipline.KNOWN_AT);

        assertThat(violations).as("结构夹具应零违规").isEmpty();
    }

    @Test
    @DisplayName("反证 D-F：移除某 AT 的 acceptance 引用 → 孤儿检测转红")
    void orphanDetectionFailsWhenAtUnreferenced(@TempDir Path temp) throws IOException {
        // 复制夹具，删掉最后一条 case 的 AT 引用（改为已覆盖的重复 AT）
        Path src = casesDir().resolve("fixture-structure.json");
        String json = Files.readString(src);
        String trimmed = json.replace("\"AT-RAG-SCALE-001\"", "\"AT-RAG-STRAT-001\"");
        assertThat(trimmed).isNotEqualTo(json);
        Path modified = temp.resolve("fixture-structure.json");
        Files.writeString(modified, trimmed);

        List<CaseSet> sets = CaseLoader.loadAll(temp);
        List<String> violations = CaseDiscipline.validate(sets, CaseDiscipline.KNOWN_AT);

        assertThat(violations).anyMatch(v -> v.contains("AT-RAG-SCALE-001")
                && v.contains("未被任何 case"));
    }

    @Test
    @DisplayName("反证 D-D：rationale 含 TBD 占位词 → 纪律断言转红")
    void rationalePlaceholderFails(@TempDir Path temp) throws IOException {
        Path src = casesDir().resolve("fixture-structure.json");
        String json = Files.readString(src);
        // 把第一条 rationale 换成含 TBD 的短串
        int idx = json.indexOf("结构验证夹具：仅验证");
        assertThat(idx).isGreaterThan(0);
        String end = json.substring(idx);
        int quote = end.indexOf('"');
        String modified = json.substring(0, idx)
                + "此 rationale 含 TBD 待补占位词且长度超过二十字符满足长度要求"
                + json.substring(idx + quote);
        Files.writeString(temp.resolve("fixture-structure.json"), modified);

        List<String> violations = CaseDiscipline.validate(
                CaseLoader.loadAll(temp), CaseDiscipline.KNOWN_AT);

        assertThat(violations).anyMatch(v -> v.contains("D-D") && v.contains("TBD"));
    }

    @Test
    @DisplayName("反证 D-A：标注者含实现方标记 → 纪律断言转红")
    void implementerAnnotatorFails(@TempDir Path temp) throws IOException {
        Path src = casesDir().resolve("fixture-structure.json");
        String json = Files.readString(src)
                .replace("fixture-struct-team-A", "impl-team-A");
        Files.writeString(temp.resolve("fixture-structure.json"), json);

        List<String> violations = CaseDiscipline.validate(
                CaseLoader.loadAll(temp), CaseDiscipline.KNOWN_AT);

        assertThat(violations).anyMatch(v -> v.contains("D-A") && v.contains("annotatorId"));
    }

    @Test
    @DisplayName("fail-closed：case 文件含未知字段 → 加载抛异常（不静默丢字段）")
    void unknownFieldFailsClosed(@TempDir Path temp) throws IOException {
        Path src = casesDir().resolve("fixture-structure.json");
        String json = Files.readString(src)
                .replace("\"layer\": \"L1\",", "\"ratoinale\": \"typo\",\n      \"layer\": \"L1\",");
        Files.writeString(temp.resolve("fixture-structure.json"), json);

        assertThatThrownBy(() -> CaseLoader.loadAll(temp))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("未知字段");
    }

    @Test
    @DisplayName("fail-closed：case 目录不存在 → 抛异常")
    void missingDirectoryFailsClosed(@TempDir Path temp) {
        assertThatThrownBy(() -> CaseLoader.loadAll(temp.resolve("nope")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("case 目录不存在");
    }
}
