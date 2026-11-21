package cn.studykid.multiagent.infrastructure.persistence.mapper;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Param;

import cn.studykid.multiagent.infrastructure.persistence.model.ModelCatalogDO;

@OpsMapper
public interface ModelCatalogMapper {

    List<ModelCatalogDO> listAll();

    Optional<ModelCatalogDO> findById(@Param("id") String id);

    int upsert(@Param("m") ModelCatalogDO m);

    Optional<ModelCatalogDO> defaultForScenario(@Param("scenario") String scenario);

    Optional<ModelCatalogDO> byScenarioAndName(@Param("scenario") String scenario,
                                                 @Param("modelName") String modelName);

    int delete(@Param("id") String id);
}
