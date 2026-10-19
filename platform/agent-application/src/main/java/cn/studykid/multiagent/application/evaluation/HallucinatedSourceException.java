package cn.studykid.multiagent.application.evaluation;

/**
 * 编造出处异常（P3-D11 / FR-020）。
 *
 * <p><b>⚠️ 这是一条 Sev-0 级失败判据</b>（P0-D00 §3.4）：
 * *「编造不存在的知识点或引用不存在的资料出处」*。
 * 触发即须阻断输出并记审计——不得降级为警告。
 */
public class HallucinatedSourceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient String rule;

    public HallucinatedSourceException(String message, String rule) {
        super(message);
        this.rule = rule;
    }

    /** @return 命中的规则名（如 {@code H-1a 章节引用}）。 */
    public String rule() {
        return rule;
    }
}