package cn.studykid.rag.premise.normalize;

import org.jspecify.annotations.NonNull;

/**
 * 一条「答案 → 题目」回指（RAG-D28 §3.3 P2-3）。
 *
 * <p>⚠️ {@code UNMATCHED} 时 {@code questionChunkId} 必须为 null——
 * 不留占位 id、不填「最像的」那道题，这是「不强绑」的可检查形式。
 *
 * <p>⚠️ 顶层类型（不得嵌套），规则来源见 {@code PremiseTopLevelTypeGuardTest}。
 *
 * @param answerChunkId  答案片段 id
 * @param questionChunkId 题目片段 id（UNMATCHED 为 null）
 * @param key            用于配对的规范题号
 * @param status         配对状态
 * @param reason         判定依据（抽检/留痕用）
 */
public record AnswerLink(
        @NonNull String answerChunkId,
        String questionChunkId,
        @NonNull ExerciseKey key,
        @NonNull PairingStatus status,
        @NonNull String reason) {

    public AnswerLink {
        if (answerChunkId == null || answerChunkId.isBlank()) {
            throw new IllegalArgumentException("answerChunkId 不能为空");
        }
        if (key == null || status == null) {
            throw new IllegalArgumentException("key/status 不能为空");
        }
        if (reason == null) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        if (status == PairingStatus.UNMATCHED && questionChunkId != null) {
            throw new IllegalArgumentException("UNMATCHED 不得携带 questionChunkId（禁止强绑）");
        }
    }

    /** @return 是否配对成功 */
    public boolean matched() {
        return status != PairingStatus.UNMATCHED;
    }
}
