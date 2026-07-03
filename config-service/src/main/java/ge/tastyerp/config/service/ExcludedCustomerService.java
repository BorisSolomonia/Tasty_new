package ge.tastyerp.config.service;

import ge.tastyerp.common.util.TinValidator;
import ge.tastyerp.config.repository.ExcludedCustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Business logic for the shared exclude-from-total customer set.
 * Stores canonical ids so exclusion is robust to leading-zero variants.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcludedCustomerService {

    private final ExcludedCustomerRepository repository;

    public List<String> getAll() {
        return repository.findAll();
    }

    /** Add a customer (canonical id) to the exclude set. Idempotent. */
    public List<String> add(String customerId) {
        String canonical = TinValidator.canonicalId(customerId);
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException("customerId is required");
        }
        Set<String> ids = new LinkedHashSet<>(repository.findAll());
        if (ids.add(canonical)) {
            repository.saveAll(new ArrayList<>(ids));
            log.info("Excluded customer {}", canonical);
        }
        return new ArrayList<>(ids);
    }

    /** Remove a customer from the exclude set. Idempotent. */
    public List<String> remove(String customerId) {
        String canonical = TinValidator.canonicalId(customerId);
        Set<String> ids = new LinkedHashSet<>(repository.findAll());
        if (ids.remove(canonical)) {
            repository.saveAll(new ArrayList<>(ids));
            log.info("Un-excluded customer {}", canonical);
        }
        return new ArrayList<>(ids);
    }
}
