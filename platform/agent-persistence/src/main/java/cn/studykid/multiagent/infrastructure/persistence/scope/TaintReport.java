package cn.studykid.multiagent.infrastructure.persistence.scope;

/**
 * 染色校验汇总（12 文 §4.5 / D6）。
 */
public record TaintReport(long rowsChecked, long violations) {

    /** 是否零违例。 */
    public boolean clean() {
        return violations == 0L;
    }
}
