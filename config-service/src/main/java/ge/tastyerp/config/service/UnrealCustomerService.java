package ge.tastyerp.config.service;

import ge.tastyerp.common.util.TinValidator;
import ge.tastyerp.config.repository.UnrealCustomerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Business logic for the shared "unreal / exception" customer set (Audit
 * Control). Stores canonical ids so a customer stays flagged regardless of
 * leading-zero TIN variants.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnrealCustomerService {

    private final UnrealCustomerRepository repository;

    public List<String> getAll() {
        return repository.findAll();
    }

    /** Mark a customer (canonical id) as unreal. Idempotent. */
    public List<String> add(String customerId) {
        String canonical = TinValidator.canonicalId(customerId);
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException("customerId is required");
        }
        Set<String> ids = new LinkedHashSet<>(repository.findAll());
        if (ids.add(canonical)) {
            repository.saveAll(new ArrayList<>(ids));
            log.info("Marked customer {} as unreal", canonical);
        }
        return new ArrayList<>(ids);
    }

    /** Un-mark a customer (back to real). Idempotent. */
    public List<String> remove(String customerId) {
        String canonical = TinValidator.canonicalId(customerId);
        Set<String> ids = new LinkedHashSet<>(repository.findAll());
        if (ids.remove(canonical)) {
            repository.saveAll(new ArrayList<>(ids));
            log.info("Un-marked customer {} (back to real)", canonical);
        }
        return new ArrayList<>(ids);
    }
}
