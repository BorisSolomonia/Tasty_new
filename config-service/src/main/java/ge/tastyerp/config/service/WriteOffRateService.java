package ge.tastyerp.config.service;

import ge.tastyerp.common.dto.audit.ProductHierarchy;
import ge.tastyerp.common.dto.config.WriteOffRateDto;
import ge.tastyerp.config.repository.WriteOffRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Business logic for per-category "possible write-off" rates.
 *
 * One rate per write-off category (BEEF, PORK). Passthrough categories (FAT,
 * OTHER) never carry a write-off and are rejected. Rates are a percentage of
 * purchased kg in {@code [0, 100]}; categories without a stored rate default to
 * {@link #DEFAULT_PERCENT}%.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WriteOffRateService {

    /** Default rate applied when a category has no stored override. */
    public static final BigDecimal DEFAULT_PERCENT = new BigDecimal("28");
    private static final BigDecimal MAX_PERCENT = new BigDecimal("100");

    private final WriteOffRateRepository repository;

    /**
     * Every write-off category with its effective rate: stored value when present,
     * else {@link #DEFAULT_PERCENT}. Guarantees the UI always has a value to seed.
     */
    public List<WriteOffRateDto> getAll() {
        Map<String, BigDecimal> stored = new LinkedHashMap<>();
        for (WriteOffRateDto r : repository.findAll()) {
            if (r.getCategory() != null && r.getPercent() != null) {
                stored.put(r.getCategory(), r.getPercent());
            }
        }
        List<WriteOffRateDto> result = new ArrayList<>();
        for (String category : ProductHierarchy.parents()) {
            if (!ProductHierarchy.appliesWriteOff(category)) {
                continue;
            }
            result.add(WriteOffRateDto.builder()
                    .category(category)
                    .percent(stored.getOrDefault(category, DEFAULT_PERCENT))
                    .build());
        }
        return result;
    }

    /** Upsert the rate for one write-off category (one rate per category). */
    public WriteOffRateDto setRate(String category, BigDecimal percent) {
        if (category == null || !ProductHierarchy.appliesWriteOff(category)) {
            throw new IllegalArgumentException("Write-off rate applies only to categories: "
                    + ProductHierarchy.parents() + " that apply write-off (BEEF, PORK)");
        }
        if (percent == null || percent.compareTo(BigDecimal.ZERO) < 0 || percent.compareTo(MAX_PERCENT) > 0) {
            throw new IllegalArgumentException("percent must be between 0 and 100");
        }

        List<WriteOffRateDto> all = new ArrayList<>(repository.findAll());
        all.removeIf(r -> category.equals(r.getCategory()));
        WriteOffRateDto saved = WriteOffRateDto.builder()
                .category(category)
                .percent(percent)
                .build();
        all.add(saved);
        repository.saveAll(all);
        log.info("Set write-off rate: {} -> {}%", category, percent.toPlainString());
        return saved;
    }
}
