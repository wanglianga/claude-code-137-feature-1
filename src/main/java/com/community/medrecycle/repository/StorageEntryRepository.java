package com.community.medrecycle.repository;

import com.community.medrecycle.domain.StorageEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StorageEntryRepository extends JpaRepository<StorageEntry, Long> {
    Optional<StorageEntry> findByBagIdAndOutAtIsNull(Long bagId);
    List<StorageEntry> findBySiteIdAndOutAtIsNull(Long siteId);
    /** 超期暂存：入柜时间早于 cutoff 且未出柜 */
    List<StorageEntry> findBySiteIdAndOutAtIsNullAndInAtBefore(Long siteId, LocalDateTime cutoff);
}
