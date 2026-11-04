package cn.studykid.rag.management.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 元数据确认命令的不变式（交互文档 §4.3 / V1.0.2 §5 R1）。
 *
 * <p>⚠️ 守三件事：
 * <ol>
 *   <li><b>书名/出版社必填</b>——确认一份没有书名的元数据没有意义；</li>
 *   <li><b>册次必须与 DDL 同口径</b>——否则落库时才报一个用户读不懂的约束名；</li>
 *   <li><b>空白串归一为 null</b>——「没填」只能有一种表示，否则
 *       「空 ISBN」与「无 ISBN」在审计里会长得不一样。</li>
 * </ol>
 */
class MetadataConfirmationTest {

    @Test
    @DisplayName("合法输入原样保留")
    void keepsValidValues() {
        MetadataConfirmation info = new MetadataConfirmation(
                "沪教版英语六年级上册", "上海教育出版社", "9787544400000",
                "英语", "六年级", "上册", "版权页核对");

        assertEquals("沪教版英语六年级上册", info.title());
        assertEquals("上海教育出版社", info.publisher());
        assertEquals("9787544400000", info.isbn());
        assertEquals("英语", info.subject());
        assertEquals("六年级", info.grade());
        assertEquals("上册", info.term());
        assertEquals("版权页核对", info.note());
    }

    @Test
    @DisplayName("书名不能为空")
    void rejectsBlankTitle() {
        assertThrows(IllegalArgumentException.class, () -> new MetadataConfirmation(
                "  ", "上海教育出版社", null, null, null, null, null));
    }

    @Test
    @DisplayName("出版社不能为空")
    void rejectsBlankPublisher() {
        assertThrows(IllegalArgumentException.class, () -> new MetadataConfirmation(
                "沪教版英语六年级上册", "", null, null, null, null, null));
    }

    @Test
    @DisplayName("册次非法值被拦下（与 rag_edition.term 的 CHECK 同口径）")
    void rejectsIllegalTerm() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new MetadataConfirmation(
                        "沪教版英语六年级上册", "上海教育出版社", null, null, null, "中册", null));
        assertEquals(true, ex.getMessage().contains("上册"));
    }

    @Test
    @DisplayName("册次三种合法值都接受")
    void acceptsAllLegalTerms() {
        for (String term : new String[] {"上册", "下册", "全一册"}) {
            MetadataConfirmation info = new MetadataConfirmation(
                    "书名", "出版社", null, null, null, term, null);
            assertEquals(term, info.term());
        }
    }

    @Test
    @DisplayName("空白串归一为 null：'没填'只有一种表示")
    void normalizesBlankToNull() {
        MetadataConfirmation info = new MetadataConfirmation(
                "书名", "出版社", "   ", "", "  ", "  ", " ");

        assertNull(info.isbn());
        assertNull(info.subject());
        assertNull(info.grade());
        assertNull(info.term());
        assertNull(info.note());
    }

    @Test
    @DisplayName("字段两侧空白被裁剪")
    void trimsWhitespace() {
        MetadataConfirmation info = new MetadataConfirmation(
                "书名", "出版社", "  9787544400000  ", null, null, " 上册 ", null);

        assertEquals("9787544400000", info.isbn());
        // ⚠️ 册次裁白后仍要能命中白名单
        assertEquals("上册", info.term());
    }

    @Test
    @DisplayName("ISBN 可空（论文类语料本就不适用）")
    void allowsNullIsbn() {
        MetadataConfirmation info = new MetadataConfirmation(
                "论文标题", "期刊社", null, null, null, null, null);

        assertNull(info.isbn());
    }
}
