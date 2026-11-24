package cn.studykid.rag.infrastructure.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link MrlProjector} 单测：MRL 前缀截断 + L2 重新归一化（D29 DEC-P2 S1）。
 *
 * <p>覆盖纪律：① 只取前缀；② 截断后 L2 范数≈1；③ 禁止零填充放大维度；
 * ④ 拒绝零向量/非有限数。撤掉归一化即可观察到范数≠1（反证用例见
 * {@link #truncationRenormalizesToUnitNorm()}，它对比了「截断不归一」的漂移）。
 */
class MrlProjectorTest {

    @Test
    void takesPrefixOnly() {
        float[] source = new float[8];
        for (int i = 0; i < source.length; i++) {
            source[i] = i + 1; // 1..8
        }
        float[] out = MrlProjector.project(source, 4);

        assertEquals(4, out.length);
        // 前 4 个方向应与源前 4 维同向（归一化后只是整体缩放）
        assertTrue(out[0] > 0 && out[1] > out[0] && out[2] > out[1] && out[3] > out[2]);
        assertEquals(5f, source[4], 1e-6, "不得修改入参");
    }

    @Test
    void truncationRenormalizesToUnitNorm() {
        float[] source = new float[4096];
        // 模拟真实分布：前段分量较大，后段较小。若截断后不归一化，范数会明显 <1。
        for (int i = 0; i < source.length; i++) {
            source[i] = (i < 1024 ? 1.0f : 0.01f) * (i % 7 + 1);
        }
        float[] projected = MrlProjector.project(source, 1024);

        assertEquals(1024, projected.length);
        assertEquals(1.0, MrlProjector.l2Norm(projected), 1e-5,
                "截断后必须重新 L2 归一化到单位范数");

        // ⚠️ 反证锚点：只截断不归一化时，前 1024 维的范数远小于 1 → 分数分布漂移。
        double unnormalized = 0.0;
        for (int i = 0; i < 1024; i++) {
            unnormalized += (double) source[i] * source[i];
        }
        unnormalized = Math.sqrt(unnormalized);
        assertNotEquals(1.0, unnormalized, 1e-3,
                "若该断言失败，说明本用例构造的源向量截断后恰好已是单位范数，反证失效，需换数据");
        assertTrue(unnormalized > 2.0, "未归一化的前缀范数应显著偏离 1（实测 " + unnormalized + "）");
    }

    @Test
    void fullDimensionIsIdentityNorm() {
        float[] source = {3f, 4f, 0f, 0f};
        float[] out = MrlProjector.project(source, 4);
        assertEquals(1.0, MrlProjector.l2Norm(out), 1e-6);
        assertEquals(0.6f, out[0], 1e-6);
        assertEquals(0.8f, out[1], 1e-6);
    }

    @Test
    void rejectsZeroPaddingAttempt() {
        float[] source = {1f, 2f};
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MrlProjector.project(source, 4096));
        assertTrue(e.getMessage().contains("零填充"), e.getMessage());
    }

    @Test
    void rejectsNonPositiveTarget() {
        float[] source = {1f, 2f};
        assertThrows(IllegalArgumentException.class, () -> MrlProjector.project(source, 0));
        assertThrows(IllegalArgumentException.class, () -> MrlProjector.project(source, -1));
    }

    @Test
    void rejectsZeroVector() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MrlProjector.project(new float[16], 8));
        assertTrue(e.getMessage().contains("零向量"), e.getMessage());
    }

    @Test
    void rejectsNonFiniteComponent() {
        float[] source = {1f, Float.NaN, 3f};
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> MrlProjector.project(source, 3));
        assertTrue(e.getMessage().contains("非有限"), e.getMessage());
    }
}
