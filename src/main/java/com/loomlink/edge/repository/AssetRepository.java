package com.loomlink.edge.repository;

import com.loomlink.edge.domain.enums.EquipmentClass;
import com.loomlink.edge.domain.model.Asset;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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
    List<Asset> findFleetSiblings(@Param("manufacturer") String manufacturer,
                                   @Param("modelNumber") String modelNumber,
                                   @Param("equipmentClass") EquipmentClass equipmentClass,
                                   @Param("excludeTag") String excludeTag);

    List<Asset> findBySapPlant(String sapPlant);

    Optional<Asset> findByRedundancyPartnerTag(String partnerTag);

    List<Asset> findByEquipmentClass(EquipmentClass equipmentClass);

    /** Find assets in a facility area (partial match on functional location). */
    @Query("SELECT a FROM Asset a WHERE LOWER(a.functionalLocation) LIKE LOWER(CONCAT('%', :area, '%'))")
    List<Asset> findByFacilityArea(@Param("area") String area);
}
