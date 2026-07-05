package ge.tastyerp.config.service;

import ge.tastyerp.common.dto.config.FormalSalesCustomerDto;
import ge.tastyerp.common.util.TinValidator;
import ge.tastyerp.config.repository.FormalSalesCustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Business logic for the formal-sales customer set (BOR-76 Part 3).
 *
 * Customers are keyed by canonical (leading-zero-stripped) TIN so they match the
 * movement counterparties. Commission defaults to {@link #DEFAULT_COMMISSION}
 * GEL/kg and must be non-negative.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FormalSalesCustomerService {

    public static final BigDecimal DEFAULT_COMMISSION = new BigDecimal("0.50");

    private final FormalSalesCustomerRepository repository;

    public List<FormalSalesCustomerDto> getAll() {
        return repository.findAll();
    }

    /** Add or update a formal-sales customer (one entry per canonical id). */
    public FormalSalesCustomerDto setCustomer(FormalSalesCustomerDto request) {
        if (request == null || request.getCustomerId() == null) {
            throw new IllegalArgumentException("customerId is required");
        }
        String canonical = TinValidator.canonicalId(request.getCustomerId());
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException("customerId is required");
        }
        BigDecimal rate = request.getCommissionPerKg() != null
                ? request.getCommissionPerKg() : DEFAULT_COMMISSION;
        if (rate.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("commissionPerKg must be non-negative");
        }

        FormalSalesCustomerDto saved = FormalSalesCustomerDto.builder()
                .customerId(canonical)
                .customerName(request.getCustomerName())
                .commissionPerKg(rate)
                .build();

        List<FormalSalesCustomerDto> all = new ArrayList<>(repository.findAll());
        all.removeIf(c -> canonical.equals(c.getCustomerId()));
        all.add(saved);
        repository.saveAll(all);
        log.info("Set formal-sales customer {} @ {} GEL/kg", canonical, rate.toPlainString());
        return saved;
    }

    /** Remove a formal-sales customer. Idempotent. */
    public List<FormalSalesCustomerDto> remove(String customerId) {
        String canonical = TinValidator.canonicalId(customerId);
        List<FormalSalesCustomerDto> all = new ArrayList<>(repository.findAll());
        if (all.removeIf(c -> canonical.equals(c.getCustomerId()))) {
            repository.saveAll(all);
            log.info("Removed formal-sales customer {}", canonical);
        }
        return all;
    }
}
