package cn.studykid.multiagent.application.cap;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.NonNull;

import cn.studykid.multiagent.core.cap.CapHttpToolConfig;
import cn.studykid.multiagent.core.cap.CapMcpServerDef;
import cn.studykid.multiagent.core.cap.CapSkillDef;
import cn.studykid.multiagent.core.cap.CapToolDef;
import cn.studykid.multiagent.core.cap.ConfigObjectVersion;
import cn.studykid.multiagent.core.cap.ConfigPublishCommand;
import cn.studykid.multiagent.core.cap.ReasonCode;

/**
 * 配置期校验门禁（§6.3 / §6.4 / §6.6 / 06 §3 · W-PERM-P5-T5）。
 *
 * <p>⚠️ <b>退出门禁「配置期 {@code riskLevel} 未知/非法拒绝发布」在此落地</b>：
 * P1 已在<b>运行期</b>把未知风险拒为 {@link ReasonCode#RISK_LEVEL_UNKNOWN}，
 * 但运行期拒绝有个致命缺陷：<b>配置已经生效了</b> ——
 * 在途 Run 按旧指纹继续跑，新 Run 全部被拒，运维看到的现象是
 * 「这个 Tool 突然全员不可用」，而真因（risk_level 写错）在配置提交那一刻就已知。
 * 配置期拒发布把问题挡在生效之前。
 *
 * <p>⚠️ <b>门禁的判定边界是「发布集合内部」</b>：
 * §6.4「缺失对象 → 发布校验失败」要求发布头里的对象集合是<b>自洽闭包</b>。
 * 跨作用域的引用（租户 Agent 引用平台 Skill）不在同一发布集合内，
 * 由平台级发布单独保证，因此这里只校验<b>同作用域内</b>的引用完整性。
 *
 * <p>⚠️ <b>本类不碰风险上限的覆盖关系</b>：
 * 「Skill 的 riskCeiling 低于其声明工具的风险」是<b>合法的收紧配置</b>
 * （§5.6 的运行期判定会给 {@code RISK_EXCEEDS_CEILING}），
 * 不是配置错误。若在配置期就拒，会把「声明得宽、限得严」这种
 * 常见且安全的写法堵死。
 */
public final class ConfigValidationGate {

    /**
     * 校验一次发布命令。
     *
     * @param command 发布命令
     * @throws ConfigPublishRejectedException 校验不通过
     */
    public void validate(@NonNull ConfigPublishCommand command) {
        validateScopeConsistency(command);
        validateTwoPersonReview(command);
        validateRiskLevels(command);
        validateMcpServers(command);
        validateHttpTools(command);
        validateReferences(command);
    }

    // ── 作用域一致性 ────────────────────────────────────────────

    private void validateScopeConsistency(@NonNull ConfigPublishCommand command) {
        if (command.isPlatformScope()) {
            if (!command.agents().isEmpty()) {
                throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN, "scope",
                        "PLATFORM 作用域的发布不得包含租户级 Agent 定义（" + command.agents().size()
                                + " 个）：平台发布头若带租户 Agent 结构，会把租户结构泄漏给所有租户"
                                + "（06 §2 平台级 vs 租户级引用）");
            }
            // ⚠️ 输出契约与路由策略同样是**租户级**对象：
            //    PLATFORM 作用域没有 tenant_id，这两类行要么写不进去（NOT NULL 违例），
            //    要么在补一个 tenant_id 后变成「平台级发布的租户对象」——
            //    两种都是结构泄漏。在门禁这里显式拒绝，别让 DB 的 NOT NULL 当成校验。
            int tenantLevelObjects = command.resultContracts().size()
                    + command.routingPolicies().size();
            if (tenantLevelObjects > 0) {
                throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN, "scope",
                        "PLATFORM 作用域的发布不得包含租户级输出契约 / 路由策略（"
                                + tenantLevelObjects + " 个）：它们按 tenant_id 寻址，"
                                + "平台发布头承载它们会造成结构泄漏");
            }
        } else {
            int platformObjects = command.tools().size() + command.skills().size()
                    + command.mcpServers().size();
            if (platformObjects > 0) {
                throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN, "scope",
                        "TENANT 作用域的发布不得包含平台级对象（" + platformObjects
                                + " 个）：平台对象由 PLATFORM 发布统一管理，"
                                + "混装会让平台版本与租户版本出现两个来源");
            }
        }
    }

    // ── 双人复核 ────────────────────────────────────────────────

    private void validateTwoPersonReview(@NonNull ConfigPublishCommand command) {
        String requester = command.requesterId();
        String approver = command.approverId();
        if (requester != null && approver != null && requester.equals(approver)) {
            throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_UNKNOWN, "approverId",
                    "申请人与审批人不得为同一主体（双人复核，06 §3 approve 行）");
        }
    }

    // ── 风险等级 ────────────────────────────────────────────────

    private void validateRiskLevels(@NonNull ConfigPublishCommand command) {
        for (CapToolDef tool : command.tools()) {
            if (tool.riskLevel().isUnknown()) {
                throw new ConfigPublishRejectedException(ReasonCode.RISK_LEVEL_UNKNOWN,
                        "riskLevel",
                        "Tool " + tool.id() + " v" + tool.version()
                                + " 的风险等级未知/非法：取值域为 READ / WRITE_PROFILE / WRITE_EXTERNAL。"
                                + "未知风险必须显式修正后才能发布（§5.6 / §5.7 RISK_LEVEL_UNKNOWN）——"
                                + "运行期拒绝已太晚，配置生效后该 Tool 将全员不可用");
            }
        }
        for (CapSkillDef skill : command.skills()) {
            if (skill.riskCeiling().isUnknown()) {
                throw new ConfigPublishRejectedException(ReasonCode.RISK_LEVEL_UNKNOWN,
                        "riskCeiling",
                        "Skill " + skill.id() + " v" + skill.version()
                                + " 的风险上限未知/非法：取值域为 READ / WRITE_PROFILE / WRITE_EXTERNAL"
                                + "（§5.6）。上限未知等同于「无法判定」，不得当作无限制");
            }
        }
    }

    // ── MCP Server 配置 ─────────────────────────────────────────

    private void validateMcpServers(@NonNull ConfigPublishCommand command) {
        for (CapMcpServerDef server : command.mcpServers()) {
            List<McpServerConfigValidator.Violation> violations =
                    McpServerConfigValidator.validate(server);
            if (!violations.isEmpty()) {
                throw new ConfigPublishRejectedException(ReasonCode.MCP_NOT_BOUND, "mcpServer",
                        "MCP Server " + server.id() + " v" + server.version() + " 配置非法: "
                                + violations);
            }
        }
    }

    // ── 远程 HTTP 工具配置（第三十七批 · §4.2 / §13.1）──────────

    /**
     * 校验远程 HTTP 工具的目标配置。
     *
     * <p>⚠️ <b>本门禁只判「形态」，不判「目标安不安全」</b>：
     * 「这个 URL 会不会解析到内网 / 云元数据地址」必须<b>等 DNS 解析之后</b>才能判定，
     * 那是<b>运行期</b>的出站防护（设计文档 §13.1）。
     * 配置期硬判 IP 有两个害处：写死 IP 段会误伤合法的内网部署；
     * 而在配置期做一次 DNS 解析，又必然与运行期的解析结果不一致（TOCTOU）——
     * 那种「看起来在挡 SSRF、实际挡不住」的检查比没有检查更危险，
     * 因为它会让人以为防线已经在了。
     *
     * <p>⚠️ <b>配置期能判的必须判干净</b>，否则这些非法配置会一路活到运行期，
     * 在那里变成「工具突然全员不可用」——配置已经生效，真因却是提交那一刻就已知。
     */
    private void validateHttpTools(@NonNull ConfigPublishCommand command) {
        for (CapToolDef tool : command.tools()) {
            CapHttpToolConfig http = tool.httpConfig();
            if (tool.isHttp() && http == null) {
                throw new ConfigPublishRejectedException(ReasonCode.HTTP_ENDPOINT_INVALID, "http",
                        "HTTP 工具 " + tool.id() + " v" + tool.version()
                                + " 缺少 HTTP 配置：方法 / URL / 超时必须齐全，"
                                + "缺一项就是一个能发布但永远跑不起来的 Tool");
            }
            if (!tool.isHttp() && http != null) {
                throw new ConfigPublishRejectedException(ReasonCode.HTTP_ENDPOINT_INVALID, "http",
                        "非 HTTP 工具 " + tool.id() + " v" + tool.version()
                                + " 携带了 HTTP 配置：目标不唯一");
            }
            if (http == null) {
                continue;
            }
            if (!CapHttpToolConfig.ALLOWED_METHODS.contains(http.method())) {
                throw new ConfigPublishRejectedException(ReasonCode.HTTP_ENDPOINT_INVALID,
                        "httpMethod",
                        "HTTP 工具 " + tool.id() + " 的方法不在白名单内: " + http.method()
                                + "（合法值: "
                                + CapHttpToolConfig.ALLOWED_METHODS.stream().sorted().toList() + "）");
            }
            if (http.timeoutMs() <= 0 || http.timeoutMs() > CapHttpToolConfig.MAX_TIMEOUT_MS) {
                throw new ConfigPublishRejectedException(ReasonCode.HTTP_ENDPOINT_INVALID,
                        "httpTimeoutMs",
                        "HTTP 工具 " + tool.id() + " 的超时必须在 1 ~ "
                                + CapHttpToolConfig.MAX_TIMEOUT_MS + " 毫秒之间: "
                                + http.timeoutMs());
            }
            validateHttpUrl(tool, http);
        }
    }

    /**
     * 校验 HTTP 目标 URL 的形态。
     *
     * <p>⚠️ <b>为什么拒绝 URL 内嵌凭据</b>（{@code https://user:pass@host/}）：
     * 凭据明文禁入库（§4.2）。内嵌在 URL 里的口令会随 URL 一起进 DB、
     * 进内容指纹、进目录回显 —— 等于把口令抄送到了好几个不该看见它的地方。
     * 需要鉴权的 HTTP 工具应当走凭据引用（KMS 注入），而不是把口令写进地址。
     */
    private static void validateHttpUrl(@NonNull CapToolDef tool, @NonNull CapHttpToolConfig http) {
        String url = http.url();
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new ConfigPublishRejectedException(ReasonCode.HTTP_ENDPOINT_INVALID,
                        "httpUrl",
                        "HTTP 工具 " + tool.id() + " 的目标 URL 含控制字符（位置 " + i + "）");
            }
        }
        java.net.URI parsed;
        try {
            parsed = java.net.URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new ConfigPublishRejectedException(ReasonCode.HTTP_ENDPOINT_INVALID, "httpUrl",
                    "HTTP 工具 " + tool.id() + " 的目标 URL 无法解析: " + url);
        }
        String scheme = parsed.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            // ⚠️ 只允许 http/https：file:// gopher:// ftp:// 这类协议是 SSRF 的经典载体，
            //    在配置期就挡掉（运行期还会再挡一次，两处都要有）。
            throw new ConfigPublishRejectedException(ReasonCode.HTTP_ENDPOINT_INVALID, "httpUrl",
                    "HTTP 工具 " + tool.id() + " 的目标协议只允许 http / https，实际为: " + scheme);
        }
        // ⚠️ getHost() 对下划线主机名等非 RFC 主机名会返回 null，
        //    那不代表「没有主机」—— 退回 authority 判空，避免误伤合法地址。
        String host = parsed.getHost() != null ? parsed.getHost() : parsed.getAuthority();
        if (host == null || host.isBlank()) {
            throw new ConfigPublishRejectedException(ReasonCode.HTTP_ENDPOINT_INVALID, "httpUrl",
                    "HTTP 工具 " + tool.id() + " 的目标 URL 缺少主机名: " + url);
        }
        int port = parsed.getPort();
        if (port != -1 && (port < 1 || port > 65535)) {
            throw new ConfigPublishRejectedException(ReasonCode.HTTP_ENDPOINT_INVALID, "httpUrl",
                    "HTTP 工具 " + tool.id() + " 的目标端口越界: " + port);
        }
        if (parsed.getUserInfo() != null) {
            throw new ConfigPublishRejectedException(ReasonCode.HTTP_ENDPOINT_INVALID, "httpUrl",
                    "HTTP 工具 " + tool.id() + " 的目标 URL 不得内嵌凭据"
                            + "（凭据明文禁入库 · §4.2；需要鉴权请走凭据引用）");
        }
    }

    // ── 引用完整性（§6.4：缺失对象 → 发布校验失败）────────────────
    private void validateReferences(@NonNull ConfigPublishCommand command) {
        if (!command.isPlatformScope()) {
            // 租户级发布只含 Agent；Agent 引用的平台 Skill 由平台级发布保证版本。
            return;
        }
        Set<String> toolIds = new HashSet<>();
        for (CapToolDef tool : command.tools()) {
            toolIds.add(tool.id());
        }
        Set<String> serverIds = new HashSet<>();
        for (CapMcpServerDef server : command.mcpServers()) {
            serverIds.add(server.id());
        }

        for (CapSkillDef skill : command.skills()) {
            for (String toolId : skill.declaredTools()) {
                if (!toolIds.contains(toolId)) {
                    throw new ConfigPublishRejectedException(ReasonCode.TOOL_NOT_DECLARED,
                            "declaredTools",
                            "Skill " + skill.id() + " 声明的 Tool " + toolId
                                    + " 不在本次发布集合中：发布头必须构成自洽闭包，"
                                    + "缺失对象须在发布校验阶段失败（§6.4）");
                }
            }
            for (CapSkillDef.McpDeclaration declaration : skill.declaredMcp()) {
                if (!serverIds.contains(declaration.serverId())) {
                    throw new ConfigPublishRejectedException(ReasonCode.MCP_NOT_BOUND,
                            "declaredMcp",
                            "Skill " + skill.id() + " 声明的 MCP Server " + declaration.serverId()
                                    + " 不在本次发布集合中：发布头必须构成自洽闭包（§6.4）");
                }
            }
        }

        for (CapToolDef tool : command.tools()) {
            if (tool.isMcp() && !serverIds.contains(tool.mcpServerId())) {
                throw new ConfigPublishRejectedException(ReasonCode.MCP_NOT_BOUND, "mcpServerId",
                        "MCP 工具 " + tool.id() + " 绑定的 Server " + tool.mcpServerId()
                                + " 不在本次发布集合中（§6.4）");
            }
        }
    }

    /**
     * 校验发布集合内是否存在重复对象引用（同一 id 出现两个版本）。
     *
     * <p>⚠️ 重复引用会让「id→精确版本」的解析<b>不确定</b> ——
     * 读路径取到哪个版本取决于遍历顺序，那等于把授权判定交给实现细节。
     *
     * @param command 发布命令
     * @throws ConfigPublishRejectedException 存在重复引用
     */
    public void validateNoDuplicateObjects(@NonNull ConfigPublishCommand command) {
        Set<String> seen = new HashSet<>();
        for (ConfigObjectVersion ref : command.objectVersions()) {
            String key = ref.type() + ':' + ref.id();
            if (!seen.add(key)) {
                throw new ConfigPublishRejectedException(ReasonCode.RESOURCE_VERSION_MISMATCH,
                        "objectVersions",
                        "发布集合内对象重复出现: type=" + ref.type() + ", id=" + ref.id()
                                + "。同一 id 只能有一个精确版本，否则 id→版本解析不确定");
            }
        }
    }
}
