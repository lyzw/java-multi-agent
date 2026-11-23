package cn.studykid.rag.eval.cases;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 一条可执行评测 case（RAG-D08 §3.3 字段规范）。
 *
 * <p>⚠️ 字段刻意保持为可空引用，以便 {@link CaseDiscipline} 逐条做标注纪律断言
 * （D-D rationale、D-E acceptance、D-A 标注者分离）——若在构造期就拒绝空值，
 * 就无法区分「哪条纪律被违反」，CI 报错会失去定位价值。
 *
 * <p>⚠️ {@code expected} 用 Map 承接而非强类型：D08 §5 的断言键按层不同
 * （L1 的 mustIncludeChunkIds / L2 的 forbidRoles / L3 的 citation 要素…），
 * 强类型会让新增断言键必须改代码；用 Map + 显式键集合校验更贴合「case 变更即测试变更」。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = false)
public class EvalCase {

    private String id;
    private String layer;
    private String trap;
    private String mode;
    private String query;
    private String documentRef;
    private Map<String, Object> expected;
    private String rationale;
    private List<String> acceptance;
    private String annotatorId;
    private String reviewedBy;
    private String changeNote;

    public String id() { return id; }
    public String layer() { return layer; }
    public String trap() { return trap; }
    public String mode() { return mode; }
    public String query() { return query; }
    public String documentRef() { return documentRef; }
    public Map<String, Object> expected() { return expected; }
    public String rationale() { return rationale; }
    public List<String> acceptance() { return acceptance; }
    public String annotatorId() { return annotatorId; }
    public String reviewedBy() { return reviewedBy; }
    public String changeNote() { return changeNote; }

    public void id(String id) { this.id = id; }
    public void layer(String layer) { this.layer = layer; }
    public void trap(String trap) { this.trap = trap; }
    public void mode(String mode) { this.mode = mode; }
    public void query(String query) { this.query = query; }
    public void documentRef(String documentRef) { this.documentRef = documentRef; }
    public void expected(Map<String, Object> expected) { this.expected = expected; }
    public void rationale(String rationale) { this.rationale = rationale; }
    public void acceptance(List<String> acceptance) { this.acceptance = acceptance; }
    public void annotatorId(String annotatorId) { this.annotatorId = annotatorId; }
    public void reviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
    public void changeNote(String changeNote) { this.changeNote = changeNote; }
}
