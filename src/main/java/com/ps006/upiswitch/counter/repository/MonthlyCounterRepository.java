package com.ps006.upiswitch.counter.repository;

import com.ps006.upiswitch.counter.entity.MonthlyCounterEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MonthlyCounterRepository extends JpaRepository<MonthlyCounterEntity, Long> {

    Optional<MonthlyCounterEntity> findByUserIdAndYearMonth(String userId, String yearMonth);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM MonthlyCounterEntity m WHERE m.userId = :userId AND m.yearMonth = :yearMonth")
    Optional<MonthlyCounterEntity> findByUserIdAndYearMonthForUpdate(
            @Param("userId") String userId,
            @Param("yearMonth") String yearMonth
    );
}

