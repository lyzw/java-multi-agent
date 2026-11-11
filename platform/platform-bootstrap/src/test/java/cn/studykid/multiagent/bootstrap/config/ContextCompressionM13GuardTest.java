package cn.studykid.multiagent.bootstrap.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 上下文压缩 <b>S2-M13「按冻结契约 mock 起步」</b>的跨前端源码守卫。
 *
 * <p>⚠️ 本类是<b>阶段守卫</b>，钉死 M13 mock 边界：两前端只解析协议 v1.1 的
 * {@code context.checkpoint} 与 {@code done.contextStateVersion}，压缩态只在内存暂存，
 * <b>⛔ 请求接口 / 请求体仍不含 {@code contextState}</b>；服务端发射与请求携带属于
 * M8 + G1 TL/QA 签核之后的正式同批发。
 *
 * <p>⚠️ 每条断言都问过「撤掉修复点它会红吗」，配套 RV-74..RV-81 撤点验证
 * （精确计数替换 + finally 复原 + md5 校验）。本类<b>不引用被测实现自己的常量</b>：
 * 事件名、字段名、锚点全部用字面量冻结，避免「改实现即改测试」的自证循环（铁律 #6）。
 *
 * <p>⛔ 所有「不得出现某模式」断言均用 {@code Pattern.find()} 取反，
 * 不用 AssertJ {@code doesNotMatch}（它是<b>全串不匹配</b>语义，长函数里恒真，是假绿源）。
 */
class ContextCompressionM13GuardTest {

    private static final List<String> TRANSPORTS = List.of(
            "admin-console/src/api/agentChatApi.ts",
            "frontend/src/api/agentChatApi.ts");
    private static final List<String> STATES = List.of(
            "admin-console/src/features/aiChat/agentChatState.ts",
            "frontend/src/features/agentChat/agentChatState.ts");

    private static final Pattern CASE = Pattern.compile("case '([a-z][a-z.]*)':");
    private static final Pattern RETURN_STATE_SPREAD =
            Pattern.compile("return\\s*\\{\\s*\\.\\.\\.state,");

    @Test
    @DisplayName("✅ RV-74：两前端恰 14 事件；checkpoint 形状 fail-closed，未知事件仍忽略")
    void bothFrontendsDecodeFourteenEventsFailClosed() {
        for (String rel : TRANSPORTS) {
            String code = stripped(functionRegion(source(rel), "export function decodeAgentFrame"));
            List<String> events = new ArrayList<>();
            Matcher m = CASE.matcher(code);
            while (m.find()) {
                events.add(m.group(1));
            }
            assertThat(events)
                    .as("⛔ %s 事件数不是 14：v1.1 只追加面被改变", rel)
                    .hasSize(14)
                    .contains("run.start", "done", "context.checkpoint");
            assertThat(code)
                    .as("⛔ %s 的 checkpoint 形状闸门或未知事件兜底被撤掉", rel)
                    .contains("switch (name)")
                    .contains("case 'context.checkpoint':")
                    .contains("contextStateVersion")
                    .contains("Number.isInteger(tier)")
                    .contains("tier < 1 || tier > 3")
                    .contains("upToTurn < 0")
                    .contains("fallback === undefined")
                    .containsPattern("default:\\s*return null");
            assertThat(code)
                    .as("⛔ %s 前端解析了 state 正文：不可信载荷不得被当作指令", rel)
                    .doesNotContain("JSON.parse(state)")
                    .doesNotContain("atob(")
                    .doesNotContain("decrypt");
        }
    }

    @Test
    @DisplayName("✅ RV-75：请求接口、发送函数与真实 JSON 请求体仍无 contextState（M13 只解析不上送）")
    void requestContractsStayByteIdentical() {
        for (String rel : TRANSPORTS) {
            String raw = source(rel);
            String req = stripped(regionBetween(raw,
                    "export interface AgentChatRequest",
                    "export interface AgentChatSendContext"));
            assertThat(req)
                    .as("⛔ %s 请求接口提前加入 contextState：M8 + G1 签核前请求字节必须逐位不变", rel)
                    .doesNotContain("contextState")
                    .doesNotContain("contextStateVersion");

            String send = stripped(functionRegion(raw, "export function sendAgentChat"));
            // ⛔ AssertJ doesNotMatch 是全串匹配语义，长函数里永远为真；
            //    必须用 Pattern.find() 取反，否则「包装函数里塞 contextState」守卫仍绿。
            assertThat(Pattern.compile("contextState\\s*:").matcher(send).find())
                    .as("⛔ %s 发送函数出现 contextState: 请求键（regex find，非全串匹配）", rel)
                    .isFalse();
            assertThat(send)
                    .as("⛔ %s 发送函数提前携带 contextState：M13 mock 只能解析不能上送", rel)
                    .doesNotContain("'contextState'")
                    .doesNotContain("\"contextState\"")
                    .doesNotContain("contextStateVersion");

            // 真正的请求字节在 liveAgentChatTransport 的 JSON.stringify 里，
            // 只扫 sendAgentChat 包装函数会漏掉「包装没变、body 已变」的静默失真。
            String body = stripped(regionBetween(raw, "body: JSON.stringify({", "}),"));
            assertThat(body)
                    .as("⛔ %s 真实请求体提前加入 contextState：M13 mock 的请求字节必须逐位不变", rel)
                    .doesNotContain("contextState")
                    .doesNotContain("contextStateVersion");
            assertThat(Pattern.compile("contextState\\s*:").matcher(body).find())
                    .as("⛔ %s 真实请求体出现 contextState: 键（regex find，非全串匹配）", rel)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("✅ RV-76：压缩态只内存暂存；非截断 done 才提交，异常 / 刷新即降级")
    void checkpointLifecycleIsMemoryOnlyAndFailClosed() {
        for (String rel : STATES) {
            String code = stripped(source(rel));
            assertThat(code)
                    .as("⛔ %s 压缩态生命周期闸门被撤掉", rel)
                    .contains("pendingContextCheckpoint")
                    .contains("contextCheckpoint: cp")
                    .contains("currentContextCheckpoint")
                    .contains("state.contextCheckpoint === undefined")
                    .contains("action.event.type === 'context.checkpoint'")
                    .contains("action.event.truncated")
                    .contains("contextStateVersion:")
                    .contains("action.event.contextStateVersion ?? next.pendingContextStateVersion")
                    .contains("pendingContextCheckpoint: undefined")
                    .contains("pendingContextStateVersion: undefined");
            assertThat(code)
                    .as("⛔ %s 压缩态被持久化或解析：不透明串不得落盘 / 当指令", rel)
                    .doesNotContain("localStorage")
                    .doesNotContain("sessionStorage")
                    .doesNotContain("JSON.parse")
                    .doesNotContain("atob(");
        }
    }

    @Test
    @DisplayName("✅ RV-77：SEND / STOP / RETRY 展开旧 state，不静默清空新增压缩态字段")
    void stateTransitionsDoNotDropCheckpointFields() {
        for (String rel : STATES) {
            String code = stripped(source(rel));
            assertThat(count(RETURN_STATE_SPREAD, code))
                    .as("⛔ %s 有 state 分支重建时未展开旧 state（铁律 #10）："
                            + "patchStreaming + SEND / STOP / RETRY 共 4 处", rel)
                    .isGreaterThanOrEqualTo(4);
            assertThat(Pattern.compile("return\\s*\\{\\s*messages\\s*:").matcher(code).find())
                    .as("⛔ %s 出现只写 messages + sending 的 state 重建：新增字段会被静默清空（regex find，非全串匹配）", rel)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("✅ RV-78：两前端同构，文档冻结 M13 mock 与 G1 门禁")
    void docsAndParityFreezeM13MockBoundary() {
        String adminTransport = stripped(source(TRANSPORTS.get(0)));
        String tenantTransport = stripped(source(TRANSPORTS.get(1)));
        String adminState = stripped(source(STATES.get(0)));
        String tenantState = stripped(source(STATES.get(1)));
        for (String anchor : List.of("case 'context.checkpoint':", "contextStateVersion",
                "case 'done':")) {
            assertThat(adminTransport).as("平台前端解码器缺锚点 %s", anchor).contains(anchor);
            assertThat(tenantTransport).as("租户前端解码器缺锚点 %s", anchor).contains(anchor);
        }
        for (String anchor : List.of("pendingContextCheckpoint", "contextCheckpoint: cp",
                "currentContextCheckpoint", "...state,")) {
            assertThat(adminState).as("平台状态机缺锚点 %s", anchor).contains(anchor);
            assertThat(tenantState).as("租户状态机缺锚点 %s", anchor).contains(anchor);
        }

        String adr = source("docs/P0/P1/ADR-013_上下文压缩统一入口与全窗口预算.md");
        String protocol = source("docs/上下文压缩SSE协议v1.1变更说明.md");
        String record = source("docs/上下文压缩实施记录_v1.0.md");
        assertThat(adr).contains("D36").contains("M13 mock").contains("不得进入 M7 接线");
        assertThat(protocol).contains("M13 mock").contains("只解析不上送")
                .contains("G1 TL/QA 签核前不进入 M7");
        assertThat(record).contains("M13 mock").contains("parse-only");
    }

    // ── 辅助 ──────────────────────────────────────────────────────

    private static String source(String rel) {
        return ContextCompressionS1GuardTest.read(repoRoot().resolve(rel));
    }

    private static String stripped(String src) {
        return ContextCompressionS1GuardTest.stripCommentsKeepStrings(src);
    }

    private static String functionRegion(String src, String signature) {
        int start = src.indexOf(signature);
        assertThat(start).as("⛔ 找不到前端函数: %s", signature).isGreaterThanOrEqualTo(0);
        int end = src.indexOf("\n}", start);
        assertThat(end).as("⛔ 找不到前端函数结束位置: %s", signature).isGreaterThan(start);
        return src.substring(start, end + 2);
    }

    private static String regionBetween(String src, String start, String end) {
        int from = src.indexOf(start);
        assertThat(from).as("⛔ 找不到区间起点: %s", start).isGreaterThanOrEqualTo(0);
        int to = src.indexOf(end, from);
        assertThat(to).as("⛔ 找不到区间终点: %s", end).isGreaterThan(from);
        return src.substring(from, to);
    }

    private static int count(Pattern pattern, String text) {
        int n = 0;
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            n++;
        }
        return n;
    }

    private static Path repoRoot() {
        return ContextCompressionS1GuardTest.repoRoot();
    }
}
