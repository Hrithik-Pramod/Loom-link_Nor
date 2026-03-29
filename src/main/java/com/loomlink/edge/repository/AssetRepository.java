package com.loomlink.edge.repository;

import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.model.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AssetRepository extends JpaRepository<Asset, UUID> {

    Optional<Asset> findByEquipmentTag(String equipmentTag);

    /** Find fleet siblings — same manufacturer, model, and equipment class. */
    @Query("SELECT a FROM Asset a WHERE a.manufacturer = :manufacturer " +
           "AND a.modelNumber = :modelNumber AND a.equipmentClass = :equipmentClass " +
           "AND a.equipmentTag != :excludeTag")
    List<Asset> findFleetSiblings(String manufacturer, String modelNumber,
                                   EquipmentClass equipmentClass, String excludeTag);

    List<Asset> findBySapPlant(String sapPlant);

    Optional<Asset> findByRedundancyPartnerTag(String partnerTag);

    List<Asset> findByEquipmentClass(EquipmentClass equipmentClass);
}
