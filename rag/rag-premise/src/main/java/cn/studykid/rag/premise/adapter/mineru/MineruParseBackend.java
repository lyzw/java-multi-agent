package cn.studykid.rag.premise.adapter.mineru;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.spi.DocumentParseBackend;

/**
 * MinerU 解析后端（premise SPI 占位——<b>真实实现在 rag-infrastructure</b>）。
 *
 * <p><b>⚠️ 真实实现的位置与原因</b>：
 * {@code cn.studykid.rag.infrastructure.premise.MineruPremiseBackend}
 * （RAG-D26 阶段 1.2 已接入，2026-11-06 实测 MinerU 4.1.7）。
 * 它住在 infrastructure 侧的原因与 {@code DoclingPremiseBackend} 相同：
 * HTTP 客户端与 JSON 解析属于外部适配，premise 保持零框架依赖；
 * 依赖方向 infrastructure → premise。
 *
 * <p><b>本类保留的价值</b>：
 * <ul>
 *   <li>premise SPI 的「占位即文档」——接入要点（Tasks API 三步、页码换算、
 *       版本号来源）固化在注释里，是设计文档 §2.2 的代码锚点；</li>
 *   <li>测试环境无 MinerU 服务时的<b>确定性失败桩</b>：
 *       调用即抛（fail-closed），比 mock 出一个空文档安全。</li>
 * </ul>
 *
 * <p><b>已接入的实现要点（详见 MineruPremiseBackend）</b>：
 * <ul>
 *   <li>接口形态：{@code POST /v1/tasks} → PUT 上传（sha256 去重秒传）→
 *       GET 轮询 → 下载 result_url（ZIP 取 middle_json.json）；
 *       实测 162 页整册约 200 秒，<b>不分段</b>（Tasks API 自身异步）；</li>
 *   <li><b>页码换算</b>：MiddleJson 的 {@code page_idx} 从 0 起 → 映射器内 +1；</li>
 *   <li>块类型：29 种（实测教材出现 12 种）→ BlockType 显式映射表；</li>
 *   <li>版本：{@code metadata.producer.version}（实测 4.1.7）写入 ParseTrace。</li>
 * </ul>
 */
public class MineruParseBackend implements DocumentParseBackend {

    /** 占位原因说明（异常消息与装配日志共用）。 */
    public static final String NOT_IMPLEMENTED_REASON =
            "MinerU 适配器尚未接入（RAG-D26 阶段 1.2 待办）：等待 MinerU 服务安装完成后实现"
                    + " Tasks API 客户端与 MiddleJson 映射器。当前占位 fail-closed，不提供解析。";

    @Override
    public @NonNull String backendId() {
        return "mineru";
    }

    @Override
    public @NonNull String backendVersion() {
        // 真实接入后取自响应的 metadata.producer.version；
        // 占位阶段返回空串（ParseTrace 契约：未知为空串，不得为 null）。
        return "";
    }

    @Override
    public @NonNull PremiseDocument parse(@NonNull String documentId, @NonNull byte[] content) {
        // ⚠️ fail-closed：占位不产出任何文档。
        //    返回空 PremiseDocument 会让下游切出 0 片段，与
        //    「解析失败」难以区分（编排器把 0 片段视为失败，但留痕会丢掉真实原因）。
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_REASON);
    }

    /**
     * @return 是否已具备真实解析能力（占位恒 false；接入后改为探测配置/连通性）
     */
    public boolean isAvailable() {
        return false;
    }
}
