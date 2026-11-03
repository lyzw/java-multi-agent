package cn.studykid.rag.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 重灌纪律的源码级守卫（V1.0.2 §4 Z11 / 不变式 3 / S2-5）。
 *
 * <p><b>⚠️ 为什么用源码扫描而不是行为测试</b>：
 * 真正的验证需要「同一片段写两次 → 查 text_jieba 是否为空」，这要求一个
 * 带 pgvector 的真库 IT。源码级断言无法证明运行时正确，但能<b>廉价地挡住最危险的回退</b>——
 * 有人把 {@code ON CONFLICT DO UPDATE ... text_jieba = ''} 删掉或改回纯 INSERT。
 * 行为级 IT 在有库环境时补（见计划文档 S2-5 的验收判据）。
 *
 * <p>这个坑有实据：D04 §5.4 明确记录过「改了切分规则但检索没变」——
 * 根因就是重灌不清 {@code text_jieba}，而 {@code ChunkIndexer} 的待索引判据是
 * {@code text_jieba='' OR 无向量行}，于是旧片段永远不被重新嵌入。
 */
class ReingestDisciplineGuardTest {

    private static final Path REPO_SRC = Path.of(
            "src/main/java/cn/studykid/rag/infrastructure/persistence/JdbcCorpusRepository.java");

    @Test
    @DisplayName("saveChunkAndEmbedding 必须 ON CONFLICT DO UPDATE 并清空 text_jieba")
    void saveChunkClearsJiebaOnConflict() throws Exception {
        String src = Files.readString(REPO_SRC, StandardCharsets.UTF_8);

        // 截取 saveChunkAndEmbedding 方法体（到下一个 @Override 之前）
        int start = src.indexOf("public void saveChunkAndEmbedding");
        assertTrue(start > 0, "找不到 saveChunkAndEmbedding，守卫失效");
        int end = src.indexOf("@Override", start);
        String body = src.substring(start, end > start ? end : src.length());

        assertTrue(body.contains("INSERT INTO rag_chunk"),
                "方法内应插入 rag_chunk");
        assertTrue(body.contains("ON CONFLICT (chunk_id) DO UPDATE"),
                "重灌同一片段必须走 upsert：纯 INSERT 会撞主键，导致重灌直接失败");
        assertTrue(body.contains("text_jieba = ''"),
                "重灌必须把 text_jieba 清空，否则 ChunkIndexer 判据不会重新嵌入（静默陈旧）");
        assertTrue(body.contains("text_jieba,"),
                "INSERT 列清单应显式包含 text_jieba（新片段初值为空串）");
    }

    @Test
    @DisplayName("upsertEmbedding 更新向量时同样不得留下陈旧分词列")
    void upsertEmbeddingKeepsConsistencyContract() throws Exception {
        String src = Files.readString(REPO_SRC, StandardCharsets.UTF_8);
        assertTrue(src.contains("ON CONFLICT (chunk_id) DO UPDATE SET"),
                "向量写入必须 upsert（换模型重索引时同一 chunk 会再次写入）");
        assertTrue(src.contains("embed_model = EXCLUDED.embed_model"),
                "embed_model 必须随向量一起更新：它是「这批向量出自哪个模型」的代际标识");
    }
}
