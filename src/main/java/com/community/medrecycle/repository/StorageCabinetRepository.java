package com.community.medrecycle.repository;

import com.community.medrecycle.domain.StorageCabinet;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StorageCabinetRepository extends JpaRepository<StorageCabinet, Long> {
    List<StorageCabinet> findBySiteId(Long siteId);

    /** 入柜并发控制：悲观锁防止容量超卖 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from StorageCabinet c where c.id = :id")
    Optional<StorageCabinet> lockById(@Param("id") Long id);
}
