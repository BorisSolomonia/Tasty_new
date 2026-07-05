package ge.tastyerp.config.service;

import ge.tastyerp.common.dto.config.ProductVatRateDto;
import ge.tastyerp.config.repository.ProductVatRateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Business logic for per-product VAT-rate overrides.
 *
 * One rate per product name (case-insensitive). Products without an override
 * default to {@link #DEFAULT_PERCENT}% at read time. Rate must be in [0, 100].
 * Mirrors {@code ProductCategoryService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductVatRateService {

    /** Standard Georgian VAT applied to every product unless overridden. */
    public static final BigDecimal DEFAULT_PERCENT = new BigDecimal("18");
    private static final BigDecimal MAX_PERCENT = new BigDecimal("100");

    private final ProductVatRateRepository repository;

    public List<ProductVatRateDto> getAll() {
        return repository.findAll();
    }

    /** Upsert a single product's VAT rate (one rate per name). */
    public ProductVatRateDto setRate(String name, BigDecimal percent) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) {
            throw new IllegalArgumentException("Product name is required");
        }
        if (percent == null || percent.compareTo(BigDecimal.ZERO) < 0 || percent.compareTo(MAX_PERCENT) > 0) {
            throw new IllegalArgumentException("percent must be between 0 and 100");
        }

        List<ProductVatRateDto> all = new ArrayList<>(repository.findAll());
        all.removeIf(r -> cleanName.equalsIgnoreCase(r.getName() == null ? "" : r.getName().trim()));
        ProductVatRateDto saved = ProductVatRateDto.builder().name(cleanName).percent(percent).build();
        all.add(saved);
        repository.saveAll(all);
        log.info("Set product VAT rate: {} -> {}%", cleanName, percent.toPlainString());
        return saved;
    }

    /** Remove an override, reverting the product to the {@link #DEFAULT_PERCENT}% default. */
    public void deleteRate(String name) {
        String cleanName = name == null ? "" : name.trim();
        List<ProductVatRateDto> all = new ArrayList<>(repository.findAll());
        if (all.removeIf(r -> cleanName.equalsIgnoreCase(r.getName() == null ? "" : r.getName().trim()))) {
            repository.saveAll(all);
            log.info("Removed product VAT rate override: {}", cleanName);
        }
    }
}
