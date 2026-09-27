package com.community.medrecycle.repository;

import com.community.medrecycle.domain.Gift;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface GiftRepository extends JpaRepository<Gift, Long> {

    /** 兑换峰值场景：悲观锁保证库存扣减串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from Gift g where g.id = :id")
    Optional<Gift> lockById(@Param("id") Long id);
}
