package cn.studykid.multiagent.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

import cn.studykid.multiagent.infrastructure.persistence.mapper.DictionaryMapper;
import cn.studykid.multiagent.infrastructure.persistence.model.DictionaryDO;
import cn.studykid.multiagent.infrastructure.persistence.model.DictionaryItemDO;
import cn.studykid.multiagent.ports.persistence.DictionaryPort;

/**
 * Dictionary Port MyBatis-Plus 实现（W2 平台域迁移）。
 *
 * <p>⚠️ <b>平台域表 + BYPASSRLS</b>：走 opsSqlSessionFactory（运维连接），
 * 不装 RLS 拦截器；表名带 schema（{@code platform.sys_dictionary}）。
 */
@Repository
@org.springframework.context.annotation.Primary
@ConditionalOnProperty(name = "persistence.mybatis.enabled", havingValue = "true")
public class MybatisDictionaryRepository implements DictionaryPort {

    private final DictionaryMapper mapper;
    private final DataSource dataSource;

    public MybatisDictionaryRepository(
            @NonNull DictionaryMapper mapper,
            @Qualifier("dataSource") @NonNull DataSource dataSource) {
        this.mapper = mapper;
        this.dataSource = dataSource;
    }

    @Override
    public @NonNull List<DictionaryRow> listDictionaries() {
        return mapper.listAll().stream().map(this::toRow).toList();
    }

    @Override
    public @NonNull Optional<DictionaryRow> findDictionary(@NonNull String dictCode) {
        return mapper.findByCode(dictCode).map(this::toRow);
    }

    @Override
    public @NonNull List<DictionaryItemRow> listItems(@NonNull String dictCode) {
        return mapper.listItems(dictCode).stream().map(this::toItemRow).toList();
    }

    @Override
    public @NonNull Optional<DictionaryItemRow> findItem(@NonNull String dictCode, @NonNull String itemCode) {
        return mapper.findItem(dictCode, itemCode).map(this::toItemRow);
    }

    @Override
    public void insertDictionary(@NonNull DictionaryRow row) {
        DictionaryDO d = new DictionaryDO();
        d.setDictCode(row.dictCode());
        d.setDictName(row.dictName());
        d.setDescription(row.description());
        d.setBuiltIn(row.builtIn());
        d.setEnabled(row.enabled());
        d.setSortOrder(row.sortOrder());
        mapper.insert(d);
    }

    @Override
    public void updateDictionary(@NonNull DictionaryRow row) {
        DictionaryDO d = new DictionaryDO();
        d.setDictCode(row.dictCode());
        d.setDictName(row.dictName());
        d.setDescription(row.description());
        d.setEnabled(row.enabled());
        d.setSortOrder(row.sortOrder());
        mapper.update(d);
    }

    @Override
    public void upsertItem(@NonNull DictionaryItemRow row) {
        DictionaryItemDO i = new DictionaryItemDO();
        i.setDictCode(row.dictCode());
        i.setItemCode(row.itemCode());
        i.setItemLabel(row.itemLabel());
        i.setSortOrder(row.sortOrder());
        i.setBuiltIn(row.builtIn());
        i.setEnabled(row.enabled());
        mapper.upsertItem(i);
    }

    @Override
    public void setItemEnabled(@NonNull String dictCode, @NonNull String itemCode, boolean enabled) {
        mapper.setItemEnabled(dictCode, itemCode, enabled);
    }

    // ── DO → Port record ──

    private DictionaryRow toRow(DictionaryDO d) {
        return new DictionaryRow(d.getDictCode(), d.getDictName(), d.getDescription(),
                d.isBuiltIn(), d.isEnabled(), d.getSortOrder());
    }

    private DictionaryItemRow toItemRow(DictionaryItemDO i) {
        return new DictionaryItemRow(i.getDictCode(), i.getItemCode(), i.getItemLabel(),
                i.getSortOrder(), i.isBuiltIn(), i.isEnabled());
    }
}
