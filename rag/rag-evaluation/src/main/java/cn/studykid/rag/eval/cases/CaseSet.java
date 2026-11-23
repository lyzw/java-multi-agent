package cn.studykid.rag.eval.cases;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 一个 case 文件（caseSet）的根对象（RAG-D08 §3.1/§3.3）。
 *
 * <p>版本与 changeNote 是 D08 的硬要求：case 变更须升版本并说明（FR-RAG-003），
 * 否则「基线快照」无法对应到具体评测集。
 */
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY,
        getterVisibility = JsonAutoDetect.Visibility.NONE,
        isGetterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonIgnoreProperties(ignoreUnknown = false)
public class CaseSet {

    private String caseSetId;
    private String layer;
    private String version;
    private String changeNote;
    private String note;
    private List<EvalCase> cases;

    public String caseSetId() { return caseSetId; }
    public String layer() { return layer; }
    public String version() { return version; }
    public String changeNote() { return changeNote; }
    public String note() { return note; }
    public List<EvalCase> cases() { return cases; }

    public void caseSetId(String caseSetId) { this.caseSetId = caseSetId; }
    public void layer(String layer) { this.layer = layer; }
    public void version(String version) { this.version = version; }
    public void changeNote(String changeNote) { this.changeNote = changeNote; }
    public void note(String note) { this.note = note; }
    public void cases(List<EvalCase> cases) { this.cases = cases; }
}
