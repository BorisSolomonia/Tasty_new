package ge.tastyerp.config.service;

import ge.tastyerp.common.dto.audit.ProductHierarchy;
import ge.tastyerp.common.dto.config.CategoryLedgerInputDto;
import ge.tastyerp.config.repository.DualLedgerInputRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Business logic for per-category dual-ledger input overrides (BOR-76).
 *
 * One entry per category. Only the nullable override fields supplied are stored;
 * documented kg is always factual and never comes from here. Numeric fields must
 * be non-negative.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DualLedgerInputService {

    private final DualLedgerInputRepository repository;

    public List<CategoryLedgerInputDto> getAll() {
        return repository.findAll();
    }

    /** Upsert the overrides for one category (one entry per category). */
    public CategoryLedgerInputDto setInput(CategoryLedgerInputDto input) {
        if (input == null || input.getCategory() == null
                || !ProductHierarchy.isValidCategory(input.getCategory())) {
            throw new IllegalArgumentException("Invalid category: "
                    + (input == null ? null : input.getCategory())
                    + " (allowed: " + ProductHierarchy.allCategories() + ")");
        }
        requireNonNegative("docPurchasePrice", input.getDocPurchasePrice());
        requireNonNegative("realPurchasePrice", input.getRealPurchasePrice());
        requireNonNegative("realPurchaseKg", input.getRealPurchaseKg());
        requireNonNegative("docSalePrice", input.getDocSalePrice());
        requireNonNegative("realSalePrice", input.getRealSalePrice());
        requireNonNegative("realSaleKg", input.getRealSaleKg());

        List<CategoryLedgerInputDto> all = new ArrayList<>(repository.findAll());
        all.removeIf(i -> input.getCategory().equals(i.getCategory()));
        all.add(input);
        repository.saveAll(all);
        log.info("Set dual-ledger input override for {}", input.getCategory());
        return input;
    }

    private static void requireNonNegative(String field, BigDecimal v) {
        if (v != null && v.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
    }
}
