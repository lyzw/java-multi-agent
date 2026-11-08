package cn.studykid.multiagent.application.evaluation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

import cn.studykid.multiagent.ports.persistence.TraceReplayPort;
import cn.studykid.multiagent.ports.persistence.TraceReplayPort.MessageView;

/**
 * L1 自动判分（P3 · G3 内容级评估的<b>规则半边</b>）。
 *
 * <p>⚠️⚠️ <b>本类判的是「链路完整性」，不是「答案正确性」</b>。
 * 详细设计 §13 第 3 项（是否有参考答案 / 题库标准解析可作为 L1 判分源）
 * <b>尚未确认</b>；在拿到判分源之前，任何声称「归因正确率」的数字都是编造。
 * 因此这里的 {@code PASS} 只表示：
 * <i>有用户提问、有非空模型回答、回答未被截断、没有工具执行失败</i>。
 * 字段命名为 {@code linkComplete} / checkerCode 为 {@code TRACE_LINK_L1}，
 * 刻意避开 "correct" / "attribution" 这类会让人误读为内容判分的词。
 *
 * <p><b>fail-closed</b>：回放数据不足（无消息 / 无 USER）时返回
 * {@link Optional#empty()} —— 不编造结论，由编排层转为「L1 覆盖缺口」入人审队列。
 *
 * <p><b>确定性</b>：同一份 transcript 永远得到同一结论（不含随机、不含时间戳），
 * 便于回放复现与守卫反向验证。证据只存摘要（sha256），不存对话原文。
 */
@Service
public class L1AttributionEvaluator {

    /** 回写 {@code TaskOutcome.checkerCode} 时使用的标识。 */
    public static final String CHECKER_CODE = "TRACE_LINK_L1";

    private final TraceReplayPort replay;

    public L1AttributionEvaluator(@NonNull TraceReplayPort replay) {
        this.replay = replay;
    }

    /** 判分结论。 */
    public record Verdict(
            @NonNull Outcome outcome,
            boolean linkComplete,
            @NonNull String signal,
            @NonNull String evidenceDigest) {
    }

    public enum Outcome {
        /** 链路完整、回答非空且未截断、无工具失败（<b>不表示答案正确</b>）。 */
        PASS,
        /** 回答被 maxOutputTokens 截断等降级信号。 */
        PARTIAL,
        /** 无回答 / 空回答 / 工具执行失败。 */
        FAIL
    }

    /**
     * 对某次 Run 的回放时间线做 L1 规则判分。
     *
     * @return 有足够事实时返回结论；事实不足返回 empty（不编造）
     */
    public @NonNull Optional<Verdict> judge(@NonNull String tenantId, @NonNull String runId) {
        List<MessageView> messages = replay.messages(tenantId, runId);
        if (messages.isEmpty()) {
            // ⚠️ 没有埋点数据：不能据此判「失败」，也不能判「成功」。
            return Optional.empty();
        }
        boolean hasUser = messages.stream().anyMatch(m -> "USER".equals(m.role()));
        if (!hasUser) {
            return Optional.empty();
        }

        List<MessageView> assistants = messages.stream()
                .filter(m -> "ASSISTANT".equals(m.role())).toList();
        if (assistants.isEmpty()) {
            return Optional.of(verdict(Outcome.FAIL, false, "NO_ASSISTANT_OUTPUT", messages));
        }

        for (MessageView m : assistants) {
            Map<String, Object> content = m.content();
            String text = str(content.get("text"));
            Object calls = content.get("toolCalls");
            boolean hasToolCalls = calls instanceof List<?> list && !list.isEmpty();
            if (text.isBlank() && !hasToolCalls) {
                return Optional.of(verdict(Outcome.FAIL, false, "EMPTY_ASSISTANT_OUTPUT", messages));
            }
        }

        boolean truncated = assistants.stream().anyMatch(m -> {
            String finish = str(m.content().get("finishReason"));
            return "LENGTH".equalsIgnoreCase(finish) || "MAX_TOKENS".equalsIgnoreCase(finish);
        });
        if (truncated) {
            // 真实实跑抓到过：maxOutputTokens=1024 → finishReason=LENGTH，
            // 回放里答案被腰斩。这不是「回答正确」，而是降级产出。
            return Optional.of(verdict(Outcome.PARTIAL, false, "TRUNCATED_OUTPUT", messages));
        }

        boolean toolFailed = messages.stream()
                .filter(m -> "TOOL".equals(m.role()))
                .anyMatch(m -> {
                    String state = str(m.content().get("state"));
                    return !state.isEmpty() && !"SUCCEEDED".equalsIgnoreCase(state);
                });
        if (toolFailed) {
            return Optional.of(verdict(Outcome.FAIL, false, "TOOL_EXECUTION_FAILED", messages));
        }

        return Optional.of(verdict(Outcome.PASS, true, "COMPLETE_TRACE", messages));
    }

    private static Verdict verdict(Outcome outcome, boolean complete, String signal,
                                   List<MessageView> messages) {
        // 证据摘要只用结构性事实（角色 / 条数 / 信号），绝不把对话原文写进 digest 的输入。
        String facts = signal + "|messages=" + messages.size()
                + "|assistant=" + messages.stream().filter(m -> "ASSISTANT".equals(m.role())).count()
                + "|tool=" + messages.stream().filter(m -> "TOOL".equals(m.role())).count()
                + "|maxSeq=" + messages.stream().mapToLong(MessageView::seq).max().orElse(0L);
        return new Verdict(outcome, complete, signal, sha256(facts));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String sha256(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }
}
