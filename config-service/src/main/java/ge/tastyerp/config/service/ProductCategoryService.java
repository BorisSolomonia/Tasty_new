package ge.tastyerp.config.service;

import ge.tastyerp.common.dto.audit.ProductHierarchy;
import ge.tastyerp.common.dto.config.ProductCategoryDto;
import ge.tastyerp.config.repository.ProductCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Business logic for product-name -> category overrides.
 *
 * One category per product name: upsert replaces any existing override for the
 * same (trimmed) name. Categories are validated against {@link ProductHierarchy}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductCategoryService {

    private final ProductCategoryRepository repository;

    public List<ProductCategoryDto> getAll() {
        return repository.findAll();
    }

    /** Upsert a single override (one category per name). */
    public ProductCategoryDto setCategory(String name, String category) {
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.isEmpty()) {
            throw new IllegalArgumentException("Product name is required");
        }
        if (!ProductHierarchy.isValidCategory(category)) {
            throw new IllegalArgumentException("Invalid category: " + category
                    + " (allowed: " + ProductHierarchy.allCategories() + ")");
        }

        List<ProductCategoryDto> all = new ArrayList<>(repository.findAll());
        all.removeIf(c -> cleanName.equalsIgnoreCase(c.getName() == null ? "" : c.getName().trim()));
        ProductCategoryDto saved = ProductCategoryDto.builder()
                .name(cleanName)
                .category(category)
                .build();
        all.add(saved);
        repository.saveAll(all);
        log.info("Set product category override: {} -> {}", cleanName, category);
        return saved;
    }

    /** Remove an override, reverting the product to auto-classification. */
    public void deleteCategory(String name) {
        String cleanName = name == null ? "" : name.trim();
        List<ProductCategoryDto> all = new ArrayList<>(repository.findAll());
        boolean removed = all.removeIf(c ->
                cleanName.equalsIgnoreCase(c.getName() == null ? "" : c.getName().trim()));
        if (removed) {
            repository.saveAll(all);
            log.info("Removed product category override: {}", cleanName);
        }
    }
}
