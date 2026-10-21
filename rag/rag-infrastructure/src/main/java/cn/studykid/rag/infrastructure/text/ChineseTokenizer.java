package cn.studykid.rag.infrastructure.text;

import java.util.ArrayList;
import java.util.List;

/**
 * 应用层中文预分词器（RAG-D02 §3.1.2 选定方案）。
 *
 * <p><b>为什么在 Java 侧分词</b>：PostgreSQL 默认分词器（甚至 {@code simple} 词典）
 * 都不切分中文。本项目选择在应用层用统一分词器切分，把结果存入
 * {@code rag_chunk.text_jieba} 列，DB 侧建
 * {@code GIN(to_tsvector('simple', text_jieba))} 索引，
 * 词法通道与稠密通道（pgvector）在 {@code JdbcRetrievalRepository} 内用 RRF 融合。
 * 这样<b>无部署耦合</b>——不引入 {@code zhparser}/{@code pg_jieba} 这类 C 扩展。
 *
 * <p><b>⚠️ 本实现是初版基线，无词典</b>：
 * 采用「中文 unigram + bigram 混合 + 拉丁/数字整块保留」的确定性切分。
 * 它能在合成夹具下让词法通道跑通，但<b>召回精度有限</b>
 * （bigram 会产生弱相关匹配）。
 * 生产级需接入词典（教材目录 / 教辅目录 / 真题题干采集，见 RAG-D09 R1-2 别名表），
 * 届时本类替换为词典分词即可，<b>接口不变</b>。
 *
 * <p>本类是纯算法、零外部依赖，可独立单测（{@code ChineseTokenizerTest}）。
 */
public final class ChineseTokenizer {

    private ChineseTokenizer() {
    }

    /**
     * 将文本切分为空格分隔的词元序列（供 {@code tsvector} 索引与查询）。
     *
     * <p>规则：
     * <ul>
     *   <li>拉丁字母 / 数字连续块 → 整体作为一个<b>小写</b>词元（保留英文术语如 {@code vertex}）；</li>
     *   <li>中文字符 → 单独成 unigram，并与相邻中文字符组合成 bigram（提高召回）；</li>
     *   <li>空白与标点 → 丢弃（作为切分边界）。</li>
     * </ul>
     *
     * @param text 原始文本
     * @return 词元序列（空格分隔）；空 / null 输入返回空串
     */
    public static String tokenize(String text) {
        List<String> tokens = tokenizeToList(text);
        if (tokens.isEmpty()) {
            return "";
        }
        return String.join(" ", tokens);
    }

    /**
     * 同 {@link #tokenize(String)}，但返回列表形式（便于测试断言与检索端复用）。
     */
    public static List<String> tokenizeToList(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        int n = text.length();
        StringBuilder latin = null;
        List<Character> han = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (isHan(c)) {
                if (latin != null && latin.length() > 0) {
                    tokens.add(latin.toString().toLowerCase());
                    latin = null;
                }
                han.add(c);
            } else if (isLatinOrDigit(c)) {
                if (!han.isEmpty()) {
                    flushHan(han, tokens);
                    han.clear();
                }
                if (latin == null) {
                    latin = new StringBuilder();
                }
                latin.append(c);
            } else {
                // 标点 / 空白：边界
                if (latin != null && latin.length() > 0) {
                    tokens.add(latin.toString().toLowerCase());
                    latin = null;
                }
                if (!han.isEmpty()) {
                    flushHan(han, tokens);
                    han.clear();
                }
            }
        }
        if (latin != null && latin.length() > 0) {
            tokens.add(latin.toString().toLowerCase());
        }
        if (!han.isEmpty()) {
            flushHan(han, tokens);
        }
        return tokens;
    }

    /**
     * 把一段连续汉字 flush 成 unigram + bigram 词元。
     *
     * <p>⚠️ 同时输出 unigram 与 bigram：unigram 保证单字可召回，
     * bigram 提升短语级精度（如「函数」「顶点」）。
     * 二者共存使词法通道在「中文专用名词」上比纯单字更准。
     */
    private static void flushHan(List<Character> han, List<String> tokens) {
        for (char c : han) {
            tokens.add(String.valueOf(c));
        }
        for (int i = 0; i + 1 < han.size(); i++) {
            tokens.add("" + han.get(i) + han.get(i + 1));
        }
    }

    private static boolean isHan(char c) {
        // CJK 统一表意文字（含扩展 A）
        return (c >= 0x4E00 && c <= 0x9FFF) || (c >= 0x3400 && c <= 0x4DBF);
    }

    private static boolean isLatinOrDigit(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
    }
}
