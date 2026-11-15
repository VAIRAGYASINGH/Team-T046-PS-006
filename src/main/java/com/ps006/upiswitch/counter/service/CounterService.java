package com.ps006.upiswitch.counter.service;

import com.ps006.upiswitch.counter.entity.DailyCounterEntity;
import com.ps006.upiswitch.counter.entity.MonthlyCounterEntity;
import com.ps006.upiswitch.counter.model.DailyCounterModel;
import com.ps006.upiswitch.counter.model.MonthlyCounterModel;
import com.ps006.upiswitch.counter.repository.DailyCounterRepository;
import com.ps006.upiswitch.counter.repository.MonthlyCounterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
@Transactional(readOnly = true)
public class CounterService {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final DailyCounterRepository dailyCounterRepository;
    private final MonthlyCounterRepository monthlyCounterRepository;

    public CounterService(DailyCounterRepository dailyCounterRepository,
                          MonthlyCounterRepository monthlyCounterRepository) {
        this.dailyCounterRepository = dailyCounterRepository;
        this.monthlyCounterRepository = monthlyCounterRepository;
    }

    public DailyCounterModel getDailyCounter(String userId, LocalDate date) {
        return dailyCounterRepository.findByUserIdAndCounterDate(userId, date)
                .map(e -> new DailyCounterModel(e.getCounterId(), e.getUserId(), e.getCounterDate(), e.getDebitCount(), e.getDebitAmount()))
                .orElse(new DailyCounterModel(null, userId, date, 0, BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY)));
    }

    public MonthlyCounterModel getMonthlyCounter(String userId, String yearMonth) {
        return monthlyCounterRepository.findByUserIdAndYearMonth(userId, yearMonth)
                .map(e -> new MonthlyCounterModel(e.getMonthlyCounterId(), e.getUserId(), e.getYearMonth(), e.getDebitCount(), e.getDebitAmount()))
                .orElse(new MonthlyCounterModel(null, userId, yearMonth, 0, BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY)));
    }

    @Transactional
    public DailyCounterEntity incrementDaily(String userId, LocalDate date, BigDecimal amount) {
        BigDecimal stdAmount = amount.setScale(2, RoundingMode.UNNECESSARY);
        DailyCounterEntity counter = dailyCounterRepository.findByUserIdAndCounterDateForUpdate(userId, date)
                .orElseGet(() -> new DailyCounterEntity(userId, date, 0, BigDecimal.ZERO.setScale(2)));

        counter.setDebitCount(counter.getDebitCount() + 1);
        counter.setDebitAmount(counter.getDebitAmount().add(stdAmount));
        return dailyCounterRepository.save(counter);
    }

    @Transactional
    public DailyCounterEntity decrementDaily(String userId, LocalDate date, BigDecimal amount) {
        BigDecimal stdAmount = amount.setScale(2, RoundingMode.UNNECESSARY);
        return dailyCounterRepository.findByUserIdAndCounterDateForUpdate(userId, date)
                .map(counter -> {
                    counter.setDebitCount(Math.max(0, counter.getDebitCount() - 1));
                    BigDecimal newAmount = counter.getDebitAmount().subtract(stdAmount);
                    counter.setDebitAmount(newAmount.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO.setScale(2) : newAmount);
                    return dailyCounterRepository.save(counter);
                })
                .orElse(null);
    }

    @Transactional
    public MonthlyCounterEntity incrementMonthly(String userId, LocalDate date, BigDecimal amount) {
        String yearMonth = date.format(MONTH_FORMATTER);
        BigDecimal stdAmount = amount.setScale(2, RoundingMode.UNNECESSARY);
        MonthlyCounterEntity counter = monthlyCounterRepository.findByUserIdAndYearMonthForUpdate(userId, yearMonth)
                .orElseGet(() -> new MonthlyCounterEntity(userId, yearMonth, 0, BigDecimal.ZERO.setScale(2)));

        counter.setDebitCount(counter.getDebitCount() + 1);
        counter.setDebitAmount(counter.getDebitAmount().add(stdAmount));
        return monthlyCounterRepository.save(counter);
    }

    @Transactional
    public MonthlyCounterEntity decrementMonthly(String userId, LocalDate date, BigDecimal amount) {
        String yearMonth = date.format(MONTH_FORMATTER);
        BigDecimal stdAmount = amount.setScale(2, RoundingMode.UNNECESSARY);
        return monthlyCounterRepository.findByUserIdAndYearMonthForUpdate(userId, yearMonth)
                .map(counter -> {
                    counter.setDebitCount(Math.max(0, counter.getDebitCount() - 1));
                    BigDecimal newAmount = counter.getDebitAmount().subtract(stdAmount);
                    counter.setDebitAmount(newAmount.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO.setScale(2) : newAmount);
                    return monthlyCounterRepository.save(counter);
                })
                .orElse(null);
    }
}

