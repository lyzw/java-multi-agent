package cn.studykid.rag.infrastructure.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 中文分词器基线测试（纯单测，无外部依赖）。
 *
 * <p>覆盖 RAG-D02 §3.1.2 选定的「应用层预分词」方案的核心不变量：
 * <ul>
 *   <li>拉丁/数字整块保留并转小写（保留英文术语）；</li>
 *   <li>中文输出 unigram + bigram（供 tsvector 索引）；</li>
 *   <li>标点与空白作为边界丢弃；</li>
 *   <li>空输入安全。</li>
 * </ul>
 */
class ChineseTokenizerTest {

    @Test
    void latinBlockKeptAndLowercased() {
        assertEquals("vertex", ChineseTokenizer.tokenize("Vertex"));
        assertTrue(ChineseTokenizer.tokenizeToList("二次函数 vertex").contains("vertex"));
    }

    @Test
    void hanEmitsUnigramAndBigram() {
        List<String> tokens = ChineseTokenizer.tokenizeToList("顶点");
        assertTrue(tokens.contains("顶"), "应含单字「顶」");
        assertTrue(tokens.contains("点"), "应含单字「点」");
        assertTrue(tokens.contains("顶点"), "应含 bigram「顶点」");
    }

    @Test
    void punctuationAndWhitespaceDropped() {
        List<String> tokens = ChineseTokenizer.tokenizeToList("二，次。函 数！");
        assertFalse(tokens.stream().anyMatch(t ->
                t.contains("，") || t.contains("。") || t.contains("！") || t.contains(" ")),
                "标点与空白不应出现在词元中");
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertEquals("", ChineseTokenizer.tokenize(null));
        assertEquals("", ChineseTokenizer.tokenize("   "));
        assertTrue(ChineseTokenizer.tokenizeToList("").isEmpty());
    }
}
