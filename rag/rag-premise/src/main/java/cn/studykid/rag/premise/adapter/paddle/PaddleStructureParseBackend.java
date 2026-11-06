package cn.studykid.rag.premise.adapter.paddle;

import org.jspecify.annotations.NonNull;

import cn.studykid.rag.premise.model.PremiseDocument;
import cn.studykid.rag.premise.spi.DocumentParseBackend;

/**
 * PP-StructureV3 解析后端（RAG-D26 §2.2）——<b>占位实现，接口已固化</b>。
 *
 * <p><b>⚠️ 占位同样 fail-closed</b>（理由见 {@code MineruParseBackend}）：
 * 抛异常而非返回空文档或静默降级。
 *
 * <p><b>接入时的已知要点（已在设计文档 §2.2 核实）</b>：
 * <ul>
 *   <li>输入：Python 产线（{@code PPStructureV3.predict}）或 PaddleX 服务化
 *       （Serving 部署后 HTTP 调用）；</li>
 *   <li>结果字段：{@code parsing_res_list}，每元素含
 *       {@code block_label / block_content / bbox / page_index}；</li>
 *   <li><b>20 类版面标签映射</b>：其中「目录 / 页眉 / 页脚 / 页码 / 脚注」
 *       直接映射 {@code BOILERPLATE}；「文档标题 / 段落标题」按层级映射
 *       {@code HEADING}；「公式 / 公式编号」映射 {@code FORMULA}；
 *       具体枚举值以接入时的实际响应为准（实现期核对）;</li>
 *   <li>版本：取产线/服务端的模型版本号写入留痕。</li>
 * </ul>
 */
public class PaddleStructureParseBackend implements DocumentParseBackend {

    /** 占位原因说明（异常消息与装配日志共用）。 */
    public static final String NOT_IMPLEMENTED_REASON =
            "PP-StructureV3 适配器尚未接入（RAG-D26 阶段 1.3 待办）："
                    + "等待 PaddleX 服务化端点或 Python 产线桥接完成后实现映射器。"
                    + "当前占位 fail-closed，不提供解析。";

    @Override
    public @NonNull String backendId() {
        return "pp-structurev3";
    }

    @Override
    public @NonNull String backendVersion() {
        // 真实接入后取产线/服务端模型版本；占位阶段返回空串。
        return "";
    }

    @Override
    public @NonNull PremiseDocument parse(@NonNull String documentId, @NonNull byte[] content) {
        // ⚠️ fail-closed：占位不产出任何文档。
        throw new UnsupportedOperationException(NOT_IMPLEMENTED_REASON);
    }

    /**
     * @return 是否已具备真实解析能力（占位恒 false）
     */
    public boolean isAvailable() {
        return false;
    }
}
