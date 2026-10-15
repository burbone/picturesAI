package com.picturesAI.catalog_service.repository;

import com.picturesAI.catalog_service.domain.CatalogUser;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CatalogUserRepository extends Repository<CatalogUser, UUID> {

    CatalogUser save(CatalogUser user);

    Optional<CatalogUser> findById(UUID id);

    @Query("SELECT u FROM CatalogUser u WHERE u.id IN :ids")
    List<CatalogUser> findByIds(@Param("ids") Collection<UUID> ids);
}