package cn.studykid.rag.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.infrastructure.persistence.VectorColumn;

/**
 * O2 反向验证：启动自检必须比对「代码声明的向量列形态」与「数据库实际列形态」。
 *
 * <p>两种合法部署形态都要放行：
 * <ul>
 *   <li>IT 探针库：占位嵌入 1024 维 + {@code vector(1024)}；</li>
 *   <li>生产活库：真实嵌入 4096 维 + {@code halfvec(4096)}。</li>
 * </ul>
 * 而错配（配置 vector/1024、库列 halfvec(4096)）必须在启动期就 fail-fast——
 * 这是 yml 默认 {@code 1024/vector} 与 DDL 列 {@code halfvec(4096)} 冲突时
 * 「启动全程无异常、首次检索才 500」的根因。
 */
class RagStartupSelfCheckVectorColumnTest {

    private static List<String> assess(VectorColumn configured, String dbType, Integer dbDim) {
        List<String> failures = new ArrayList<>();
        RagStartupSelfCheck.assessVectorColumn(failures, configured, dbType, dbDim);
        return failures;
    }

    @Test
    @DisplayName("IT 合法形态：vector/1024 配置 + vector(1024) 库列 → 通过")
    void itVector1024Passes() {
        assertEquals(List.of(), assess(new VectorColumn("vector", 1024), "vector", 1024));
    }

    @Test
    @DisplayName("生产合法形态：halfvec/4096 配置 + halfvec(4096) 库列 → 通过")
    void prodHalfvec4096Passes() {
        assertEquals(List.of(), assess(new VectorColumn("halfvec", 4096), "halfvec", 4096));
    }

    @Test
    @DisplayName("反证：配置 vector/1024 + 库列 halfvec(4096) → 类型+维度两条都报")
    void misconfiguredVectorVsHalfvecFails() {
        List<String> failures = assess(new VectorColumn("vector", 1024), "halfvec", 4096);
        assertEquals(2, failures.size(), failures.toString());
        assertTrue(failures.stream().anyMatch(f -> f.contains("类型不一致")));
        assertTrue(failures.stream().anyMatch(f -> f.contains("维度不一致")));
    }

    @Test
    @DisplayName("反证：类型一致但维度错配 → 只报维度")
    void dimensionMismatchOnly() {
        List<String> failures = assess(new VectorColumn("halfvec", 1024), "halfvec", 4096);
        assertEquals(1, failures.size(), failures.toString());
        assertTrue(failures.get(0).contains("维度不一致"));
    }

    @Test
    @DisplayName("库列未声明维度（裸 halfvec）→ 只比类型，不误报维度")
    void undeclaredDimensionOnlyChecksType() {
        assertEquals(List.of(), assess(new VectorColumn("halfvec", 4096), "halfvec", null));
    }

    @Test
    @DisplayName("format_type 解析：带 schema 前缀/不带/裸类型")
    void parseFormattedType() {
        assertEquals("halfvec", RagStartupSelfCheck.extractVectorTypeName("public.halfvec(4096)"));
        assertEquals("vector", RagStartupSelfCheck.extractVectorTypeName("vector(1024)"));
        assertEquals("vector", RagStartupSelfCheck.extractVectorTypeName("public.vector"));
        assertEquals(Integer.valueOf(4096), RagStartupSelfCheck.extractVectorDimension("public.halfvec(4096)"));
        assertEquals(Integer.valueOf(1024), RagStartupSelfCheck.extractVectorDimension("vector(1024)"));
        assertEquals(null, RagStartupSelfCheck.extractVectorDimension("public.vector"));
    }
}
