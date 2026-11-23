package cn.studykid.rag.infrastructure.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cn.studykid.rag.premise.model.PremiseBlock;
import cn.studykid.rag.premise.normalize.NormalizationRules;
import cn.studykid.rag.premise.normalize.TableMerger;

/**
 * P1-5b 端到端：MinerU 两页表格（带 continues_prev）→ mapper 产出带 grid 的块
 * → NormalizationRules 新入口 → 合并为一张逻辑表。
 *
 * <p>⚠️ 夹具按 MinerU middle_json schema 手写合成，真实教材 TEDS 待 D08 G1 复测。
 */
class CrossPageTableEndToEndTest {

    private final MineruResponseMapper mapper = new MineruResponseMapper(null);

    @Test
    @DisplayName("两页续表经 mapper + 归一化合并入口 → 一张表，表头只出现一次")
    void mineruTwoPageTableMergedThroughNormalize() {
        String middleJson = """
                {
                  "schema":"docvortex.middle",
                  "pages":[
                    {"page_idx":0,"blocks":[
                      {"type":"table","bbox":[0.1,0.1,0.9,0.5],"content":[
                        {"type":"html","content":"<table><tr><th>科目</th><th>分数</th></tr><tr><td>语文</td><td>90</td></tr></table>"}
                      ]}
                    ]},
                    {"page_idx":1,"blocks":[
                      {"type":"table","bbox":[0.1,0.1,0.9,0.5],"continues_prev":true,"content":[
                        {"type":"html","content":"<table><tr><th>科目</th><th>分数</th></tr><tr><td>数学</td><td>95</td></tr></table>"}
                      ]}
                    ]}
                  ]
                }
                """;

        List<PremiseBlock> blocks = mapper.mapToPremiseBlocks("doc-1", middleJson);
        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).hasTableGrid()).isTrue();
        assertThat(blocks.get(1).tableGrid().continuesPrev()).isTrue();

        // 新入口：页码已 1 基（mapper 已换算），开启表格合并
        List<PremiseBlock> normalized = NormalizationRules.normalize(blocks, true, true);
        assertThat(normalized).hasSize(1);
        assertThat(normalized.get(0).tableGrid().rowCount()).isEqualTo(3);
        assertThat(normalized.get(0).text())
                .contains("语文").contains("数学").contains("95");
        // 表头「科目」只出现一次（续页表头已剥除）
        assertThat(normalized.get(0).text().lines().filter(l -> l.contains("科目")).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("关闭合并开关 → 保持两块（既有契约不破坏）")
    void mergeDisabledKeepsTwoBlocks() {
        String middleJson = """
                {
                  "schema":"docvortex.middle",
                  "pages":[
                    {"page_idx":0,"blocks":[
                      {"type":"table","content":[{"type":"html","content":"<table><tr><th>科目</th></tr><tr><td>语文</td></tr></table>"}]}
                    ]},
                    {"page_idx":1,"blocks":[
                      {"type":"table","continues_prev":true,"content":[{"type":"html","content":"<table><tr><th>科目</th></tr><tr><td>数学</td></tr></table>"}]}
                    ]}
                  ]
                }
                """;
        List<PremiseBlock> blocks = mapper.mapToPremiseBlocks("doc-1", middleJson);
        List<PremiseBlock> normalized = NormalizationRules.normalize(blocks, true, false);
        assertThat(normalized).hasSize(2);
        // 直接调 TableMerger 也应合并（验证开关本身是唯一控制点）
        assertThat(TableMerger.merge(normalized)).hasSize(1);
    }
}
