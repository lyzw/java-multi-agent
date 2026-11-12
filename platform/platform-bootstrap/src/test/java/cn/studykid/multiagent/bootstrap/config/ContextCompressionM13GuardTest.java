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
 * <p>⚠️ 本类是<b>阶段守卫</b>，钉死 M13 边界：两前端只解析协议 v1.1 的
 * {@code context.checkpoint} 与 {@code done.contextStateVersion}，压缩态只在内存暂存。
 *
 * <p><b>S3-M13 正式收发升级（D53 / #65，升级不删除）</b>：服务端 M8-a/M8-b 已就绪且
 * G1 TL/QA 已对话确认放行，两前端从「只解析不上送」升级为「<b>只上送 committed state</b>」：
 * 请求 DTO / 真实 {@code JSON.stringify} body 白名单内必须出现 {@code contextState}，
 * 且只能由 hook 从 {@code currentContextCheckpoint} 注入；解码器 / 状态机仍不得解析、
 * 解码或持久化 state，响应字段 {@code contextStateVersion} 仍禁止反向进请求。
 *
 * <p>⚠️ 每条断言都问过「撤掉修复点它会红吗」，配套 RV-74..RV-82 撤点验证
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
    private static final List<String> HOOKS = List.of(
            "admin-console/src/features/aiChat/useAgentChat.ts",
            "frontend/src/features/agentChat/useAgentChat.ts");

    private static final Pattern CASE = Pattern.compile("case '([a-z][a-z.]*)':");
    private static final Pattern RETURN_STATE_SPREAD =
            Pattern.compile("return\\s*\\{\\s*\\.\\.\\.state,");
    private static final Pattern REQUEST_KEY = Pattern.compile("contextState\\s*:");

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
    @DisplayName("✅ RV-75（S3-M13 升级）：请求 DTO 与真实 JSON body 只在白名单携带 committed state")
    void requestContractsCarryOnlyCommittedState() {
        for (String rel : TRANSPORTS) {
            String raw = source(rel);
            String code = stripped(raw);
            String req = stripped(regionBetween(raw,
                    "export interface AgentChatRequest",
                    "export interface AgentChatSendContext"));
            assertThat(req)
                    .as("⛔ %s 请求接口缺少中性 state 字段：hook 注入的 committed state 无类型出口", rel)
                    .contains("contextState?: string")
                    .as("⛔ %s 响应字段 contextStateVersion 反向进入请求接口：收发方向被污染", rel)
                    .doesNotContain("contextStateVersion");

            // 包装函数仍不得自己塞请求键：唯一注入口是 hook，不是 transport 包装层。
            String send = stripped(functionRegion(raw, "export function sendAgentChat"));
            assertThat(REQUEST_KEY.matcher(send).find())
                    .as("⛔ %s 发送包装函数出现 contextState: 请求键（regex find，非全串匹配）", rel)
                    .isFalse();
            assertThat(send)
                    .as("⛔ %s 发送包装函数塞入 state 字面量：绕过了 hook 的 committed 闸门", rel)
                    .doesNotContain("'contextState'")
                    .doesNotContain("\"contextState\"")
                    .doesNotContain("contextStateVersion");

            // 真正的请求字节在 liveAgentChatTransport 的 JSON.stringify 里，
            // 只扫接口 / 包装函数会漏掉「类型有了、body 没拼」的静默空转。
            String body = stripped(regionBetween(raw, "body: JSON.stringify({", "}),"));
            assertThat(REQUEST_KEY.matcher(body).find())
                    .as("⛔ %s 真实请求体未携带 committed state：S3-M13 前端空转（regex find）", rel)
                    .isTrue();
            assertThat(body)
                    .as("⛔ %s 请求体混入响应字段 contextStateVersion：收发方向被污染", rel)
                    .doesNotContain("contextStateVersion");
            assertThat(count(REQUEST_KEY, code))
                    .as("⛔ %s contextState 请求键必须恰好出现在 body 白名单一处，实际 %s 处",
                            rel, count(REQUEST_KEY, code))
                    .isEqualTo(1);

            // 解码器仍只读不写：第 14 事件解析不得反向注入请求键。
            String decode = stripped(functionRegion(raw, "export function decodeAgentFrame"));
            assertThat(REQUEST_KEY.matcher(decode).find())
                    .as("⛔ %s 解码器出现 contextState 请求键：请求 / 响应方向被污染", rel)
                    .isFalse();
            assertThat(decode)
                    .as("⛔ %s 解码器塞入请求键字面量或解析 state 正文", rel)
                    .doesNotContain("'contextState'")
                    .doesNotContain("\"contextState\"")
                    .doesNotContain("JSON.parse(state)")
                    .doesNotContain("atob(");
        }
    }

    @Test
    @DisplayName("✅ RV-82（S3-M13 升级）：hook 是 contextState 唯一注入口；页面参数不得传 state，STOP 不构造请求")
    void hooksInjectOnlyCommittedState() {
        for (String rel : HOOKS) {
            String code = stripped(source(rel));
            assertThat(code)
                    .as("⛔ %s hook 未从 committed checkpoint 取 state：请求会空转或误送 pending", rel)
                    .contains("currentContextCheckpoint(stateRef.current)")
                    .as("⛔ %s hook 未把 state 放进请求 DTO", rel)
                    .contains("contextState: committed?.state")
                    .as("⛔ %s 页面参数仍可传 contextState：半截 / 伪造 state 会绕过提交闸门", rel)
                    .contains("Omit<AgentChatRequest, 'message' | 'history' | 'contextState'>");
            assertThat(count(REQUEST_KEY, code))
                    .as("⛔ %s hook 的请求键必须恰好一处（请求组装），实际 %s 处",
                            rel, count(REQUEST_KEY, code))
                    .isEqualTo(1);
            String stop = stripped(regionBetween(code,
                    "const stop = useCallback(", "const retry = useCallback("));
            assertThat(stop)
                    .as("⛔ %s STOP 构造了新请求：停止动作不得把半截上下文再送一次", rel)
                    .doesNotContain("sendAgentChat")
                    .as("⛔ %s STOP 未 abort 在途请求", rel)
                    .contains("controller.abort()");
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
    @DisplayName("✅ RV-78（S3-M13 升级）：两前端同构，文档同时冻结 mock 基线与正式收发升级")
    void docsAndParityFreezeM13MockBoundary() {
        String adminTransport = stripped(source(TRANSPORTS.get(0)));
        String tenantTransport = stripped(source(TRANSPORTS.get(1)));
        String adminState = stripped(source(STATES.get(0)));
        String tenantState = stripped(source(STATES.get(1)));
        String adminHook = stripped(source(HOOKS.get(0)));
        String tenantHook = stripped(source(HOOKS.get(1)));
        for (String anchor : List.of("case 'context.checkpoint':", "contextStateVersion",
                "case 'done':", "contextState?: string")) {
            assertThat(adminTransport).as("平台前端解码器 / DTO 缺锚点 %s", anchor).contains(anchor);
            assertThat(tenantTransport).as("租户前端解码器 / DTO 缺锚点 %s", anchor).contains(anchor);
        }
        for (String anchor : List.of("pendingContextCheckpoint", "contextCheckpoint: cp",
                "currentContextCheckpoint", "...state,")) {
            assertThat(adminState).as("平台状态机缺锚点 %s", anchor).contains(anchor);
            assertThat(tenantState).as("租户状态机缺锚点 %s", anchor).contains(anchor);
        }
        for (String anchor : List.of("currentContextCheckpoint(stateRef.current)",
                "contextState: committed?.state")) {
            assertThat(adminHook).as("平台 hook 缺锚点 %s", anchor).contains(anchor);
            assertThat(tenantHook).as("租户 hook 缺锚点 %s", anchor).contains(anchor);
        }

        String adr = source("docs/P0/P1/ADR-013_上下文压缩统一入口与全窗口预算.md");
        String protocol = source("docs/上下文压缩SSE协议v1.1变更说明.md");
        String record = source("docs/上下文压缩实施记录_v1.0.md");
        String shadow = source("docs/上下文压缩影子对照零差异报告_v1.0.md");
        // 旧 mock 基线锚点保留，防止「升级即删历史」；新锚点证明正式收发放行已登记。
        assertThat(adr).contains("D36").contains("M13 mock").contains("不得进入 M7 接线")
                .contains("D53").contains("S3-M13 正式收发").contains("committed")
                .contains("不解析").contains("不持久化");
        assertThat(protocol).contains("M13 mock").contains("只解析不上送")
                .contains("G1 TL/QA 签核前不进入 M7")
                .contains("S3-M13 正式收发").contains("contextState")
                .contains("不解析").contains("不持久化").contains("旧客户端忽略");
        assertThat(record).contains("M13 mock").contains("parse-only")
                .contains("S3-M13 两前端正式收发").contains("#65");
        assertThat(shadow).contains("#65").contains("RV-M13-01");
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
