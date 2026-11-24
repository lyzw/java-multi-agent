package cn.studykid.rag.premise.normalize;

/**
 * 题答配对结果状态（RAG-D28 §3.3 P2-3）。
 *
 * <p>⚠️ {@code UNMATCHED} 是终态，不是「待重试」：配对失败时不得给答案块
 * 挂一个「看起来最接近」的题号，否则复习模式下答案会回指错题（静默错误）。
 */
public enum PairingStatus {
    /** 同册内配对成功（题目与答案在同一文档）。 */
    SAME_DOCUMENT,
    /** 跨册配对成功（edition 五轴 + 章节/试卷 + 规范题号全部相等）。 */
    CROSS_DOCUMENT,
    /** 配对失败，不回指任何题目。 */
    UNMATCHED
}
