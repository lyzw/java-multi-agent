package cn.studykid.multiagent.ports.tool;

/** 工具输入校验失败（Schema 不符）。禁止重试，属调用方错误。 */
public class ToolValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ToolValidationException(String message) {
        super(message);
    }
}
