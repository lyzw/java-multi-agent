package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import cn.studykid.multiagent.infrastructure.persistence.model.DictionaryDO;
import cn.studykid.multiagent.infrastructure.persistence.model.DictionaryItemDO;

/**
 * Dictionary Mapper —— 平台域表，需要 opsSqlSessionFactory（BYPASSRLS 运维连接）。
 *
 * <p>表名带 schema（{@code platform.sys_dictionary}），ON CONFLICT + CAS + RETURNING 全部原样保留。
 */
@OpsMapper
public interface DictionaryMapper {

    List<DictionaryDO> listAll();

    Optional<DictionaryDO> findByCode(@Param("dictCode") String dictCode);

    List<DictionaryItemDO> listItems(@Param("dictCode") String dictCode);

    Optional<DictionaryItemDO> findItem(@Param("dictCode") String dictCode,
                                        @Param("itemCode") String itemCode);

    int insert(@Param("dict") DictionaryDO dict);

    int update(@Param("dict") DictionaryDO dict);

    int upsertItem(@Param("item") DictionaryItemDO item);

    int setItemEnabled(@Param("dictCode") String dictCode,
                       @Param("itemCode") String itemCode,
                       @Param("enabled") boolean enabled);
}
