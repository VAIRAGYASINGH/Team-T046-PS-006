package com.ps006.upiswitch.common.repository;

import com.ps006.upiswitch.common.entity.HolidayEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public interface HolidayRepository extends JpaRepository<HolidayEntity, LocalDate> {
}

