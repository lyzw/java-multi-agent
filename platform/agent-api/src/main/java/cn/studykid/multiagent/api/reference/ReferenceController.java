package cn.studykid.multiagent.api.reference;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.studykid.multiagent.api.run.RunController.CurrentPrincipal;
import cn.studykid.multiagent.core.reference.TutoringVocabulary;

/**
 * 业务字典端点（FE-01 协作项 B3）。
 *
 * <p>返回平台<b>辅导业务</b>的学科/年级词表，作为前端下拉选项的<b>单一真相源</b>，
 * 取代 {@code NewRunPage} 里硬编码的 {@code SUBJECTS}/{@code GRADES} 常量，
 * 避免前后端词表漂移导致「漏召回 / 提交被拒」。
 *
 * <p>⚠️ <b>与 RAG 版本枚举的区别</b>：本端点是<b>平台辅导业务</b>词表（初中理科为主），
 * 而 RAG 检索页词表（{@code RAG_SUBJECTS}/{@code RAG_GRADES}）来自
 * {@code rag-core} 的 {@code EditionCoordinate} 枚举（覆盖小学到高中），
 * 二者语义不同，不可混用——本端点<b>不</b>服务于 RAG 检索。
 *
 * <p>⚠️ <b>只读、中性、无需特定业务权限</b>：任何已登录（已验签）用户可见；
 * 调用 {@link CurrentPrincipal#context()} 仅用于触发令牌校验（无令牌 → 401）。
 * 词表本身与租户无关，故不读取 {@code tenantId} 做过滤。
 */
@RestController
@RequestMapping("/api/reference")
public class ReferenceController {

    /**
     * 平台辅导业务学科词表（DEC-6 采集白名单：仅学科）。
     *
     * <p>⚠️ 故意不暴露「历史/地理/道法」等：平台当前辅导业务聚焦初中国家课程
     * 理科与语英，词表即业务边界。
     *
     * <p>⚠️ <b>词表本体在 {@code agent-core} 的 {@link TutoringVocabulary}</b>，
     * 不在本类里再存一份：Run 列表回填学科时要用<b>同一份</b>词表做 fail-closed 校验，
     * 两份拷贝迟早漂移（前端能选、后端不认 → 提交成功但列表查不出来）。
     */
    private static final List<String> SUBJECTS = TutoringVocabulary.SUBJECTS;

    /** 平台辅导业务年级词表（同上：与 Run 列表共用一份）。 */
    private static final List<String> GRADES = TutoringVocabulary.GRADES;

    private final CurrentPrincipal currentPrincipal;

    public ReferenceController(CurrentPrincipal currentPrincipal) {
        this.currentPrincipal = currentPrincipal;
    }

    @GetMapping
    public ReferenceResponse reference() {
        // 仅触发令牌校验；词表与租户无关，不读取 tenantId。
        currentPrincipal.context();
        return new ReferenceResponse(SUBJECTS, GRADES);
    }

    /**
     * 字典响应。
     *
     * <p>⚠️ 字段名 camelCase（record 直接序列化，零转换），与前端约定一致。
     */
    public record ReferenceResponse(List<String> subjects, List<String> grades) {
    }
}
