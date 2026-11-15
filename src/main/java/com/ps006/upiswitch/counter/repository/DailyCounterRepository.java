package com.ps006.upiswitch.counter.repository;

import com.ps006.upiswitch.counter.entity.DailyCounterEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;

@Repository
public interface DailyCounterRepository extends JpaRepository<DailyCounterEntity, Long> {

    Optional<DailyCounterEntity> findByUserIdAndCounterDate(String userId, LocalDate counterDate);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DailyCounterEntity d WHERE d.userId = :userId AND d.counterDate = :counterDate")
    Optional<DailyCounterEntity> findByUserIdAndCounterDateForUpdate(
            @Param("userId") String userId,
            @Param("counterDate") LocalDate counterDate
    );
}

