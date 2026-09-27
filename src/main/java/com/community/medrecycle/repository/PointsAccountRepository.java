package com.community.medrecycle.repository;

import com.community.medrecycle.domain.PointsAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PointsAccountRepository extends JpaRepository<PointsAccount, Long> {
    Optional<PointsAccount> findByResidentId(Long residentId);

    /** 兑换峰值场景：悲观锁保证余额扣减串行化 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PointsAccount a where a.residentId = :residentId")
    Optional<PointsAccount> lockByResidentId(@Param("residentId") Long residentId);
}
