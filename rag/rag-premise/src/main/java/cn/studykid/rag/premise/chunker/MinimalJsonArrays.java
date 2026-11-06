package cn.studykid.rag.premise.chunker;

import java.util.ArrayList;
import java.util.List;

/**
 * 受限 JSON 数组/字段提取器（premise 零框架依赖的权宜实现）。
 *
 * <p><b>⚠️ 适用范围（超此范围禁用，先改桥或引依赖）</b>：
 * 只为解析分块桥的<b>已知锁定结构</b>——
 * 顶层对象含 {@code chunks: [ { text, headings[], page_numbers[],
 * char_spans:[{start,end}], item_refs[] } ]}。
 * 元素值为标量或同质一层数组；<b>不支持</b>嵌套对象取值、转义引号内的对象、
 * 任意 JSON 路径。结构扩展须先改桥的 response_model，再改此处
 * （{@code HybridChunkerClientTest} 用真实样张锁形状）。
 *
 * <p><b>转义处理</b>：字符串值内的 {@code \"} 与 {@code \\} 已处理；
 * 桥侧 pydantic 保证文本不含未转义控制符（JSON 规范）。
 */
final class MinimalJsonArrays {

    private MinimalJsonArrays() {
    }

    /**
     * @param json  完整响应
     * @param field 顶层字段名（值为对象数组）
     * @return 各对象元素的原始 JSON 串
     */
    static List<String> objectsOfArray(String json, String field) {
        List<String> out = new ArrayList<>();
        String arr = arrayBodyOf(json, field);
        int i = 0;
        while (i < arr.length()) {
            char c = arr.charAt(i);
            if (c != '{') {
                i++;
                continue;
            }
            int end = matchBrace(arr, i, '{', '}');
            out.add(arr.substring(i, end + 1));
            i = end + 1;
        }
        return out;
    }

    /** @return 顶层对象数组字段里每个字符串元素的值列表 */
    static List<String> stringArrayOf(String json, String field) {
        List<String> out = new ArrayList<>();
        String arr = arrayBodyOf(json, field);
        int i = 0;
        while (i < arr.length()) {
            char c = arr.charAt(i);
            if (c != '"') {
                i++;
                continue;
            }
            int end = matchString(arr, i);
            out.add(unescape(arr.substring(i + 1, end)));
            i = end + 1;
        }
        return out;
    }

    /** @return 顶层对象数组字段里每个整数元素（缺失/非数字用 fallback） */
    static List<Integer> intArrayOf(String json, String field, int fallback) {
        List<Integer> out = new ArrayList<>();
        String arr = arrayBodyOf(json, field);
        StringBuilder num = new StringBuilder();
        for (int i = 0; i <= arr.length(); i++) {
            char c = i < arr.length() ? arr.charAt(i) : ',';
            if (c >= '0' && c <= '9') {
                num.append(c);
            } else if (num.length() > 0) {
                try {
                    out.add(Integer.parseInt(num.toString()));
                } catch (NumberFormatException e) {
                    out.add(fallback);
                }
                num.setLength(0);
            }
        }
        return out;
    }

    /** @return 对象里字符串字段的值（缺失返回空串） */
    static String stringValue(String obj, String field) {
        int keyIdx = findKey(obj, field);
        if (keyIdx < 0) {
            return "";
        }
        int colon = obj.indexOf(':', keyIdx + field.length());
        if (colon < 0) {
            return "";
        }
        int i = colon + 1;
        while (i < obj.length() && Character.isWhitespace(obj.charAt(i))) {
            i++;
        }
        if (i >= obj.length() || obj.charAt(i) != '"') {
            return "";
        }
        int end = matchString(obj, i);
        return unescape(obj.substring(i + 1, end));
    }

    /** @return 对象里整数字段的值（缺失返回 fallback） */
    static int intValue(String obj, String field, int fallback) {
        int keyIdx = findKey(obj, field);
        if (keyIdx < 0) {
            return fallback;
        }
        int colon = obj.indexOf(':', keyIdx + field.length());
        if (colon < 0) {
            return fallback;
        }
        int i = colon + 1;
        while (i < obj.length() && !Character.isDigit(obj.charAt(i))
                && obj.charAt(i) != '-') {
            i++;
        }
        int start = i;
        while (i < obj.length() && (Character.isDigit(obj.charAt(i)) || obj.charAt(i) == '-')) {
            i++;
        }
        try {
            return Integer.parseInt(obj.substring(start, i));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---------- 内部 ----------

    private static int findKey(String json, String field) {
        String needle = "\"" + field + "\"";
        return json.indexOf(needle);
    }

    private static String arrayBodyOf(String json, String field) {
        int keyIdx = findKey(json, field);
        if (keyIdx < 0) {
            return "";
        }
        int bracket = json.indexOf('[', keyIdx + field.length());
        if (bracket < 0) {
            return "";
        }
        int end = matchBrace(json, bracket, '[', ']');
        return json.substring(bracket + 1, end);
    }

    /** 匹配配对括号（跳过字符串字面量）。 */
    private static int matchBrace(String s, int open, char openCh, char closeCh) {
        int depth = 0;
        boolean inStr = false;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == openCh) {
                depth++;
            } else if (c == closeCh) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return s.length() - 1;
    }

    /** 字符串字面量的结束下标（处理转义）。 */
    private static int matchString(String s, int openQuote) {
        for (int i = openQuote + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"') {
                return i;
            }
        }
        return s.length() - 1;
    }

    private static String unescape(String raw) {
        if (raw.indexOf('\\') < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char n = raw.charAt(++i);
                switch (n) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        if (i + 4 < raw.length()) {
                            sb.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
                            i += 4;
                        }
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
