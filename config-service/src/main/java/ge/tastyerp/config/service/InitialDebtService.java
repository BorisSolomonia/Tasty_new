package ge.tastyerp.config.service;

import ge.tastyerp.common.dto.config.InitialDebtDto;
import ge.tastyerp.common.exception.DuplicateResourceException;
import ge.tastyerp.common.exception.ResourceNotFoundException;
import ge.tastyerp.common.exception.ValidationException;
import ge.tastyerp.common.util.TinValidator;
import ge.tastyerp.config.repository.InitialDebtRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing initial customer debts.
 *
 * ALL business logic for initial debt management is here.
 * Controllers only delegate to this service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InitialDebtService {

    private final InitialDebtRepository initialDebtRepository;

    /**
     * Get all initial debts.
     */
    public List<InitialDebtDto> getAllDebts() {
        log.debug("Fetching all initial debts");
        return initialDebtRepository.findAll();
    }

    /**
     * Get initial debt for a specific customer.
     */
    public InitialDebtDto getDebt(String customerId) {
        String normalizedId = TinValidator.normalize(customerId);
        log.debug("Fetching initial debt for customer: {}", normalizedId);

        return initialDebtRepository.findByCustomerId(normalizedId)
                .orElseThrow(() -> new ResourceNotFoundException("Initial debt", normalizedId));
    }

    /**
     * Get initial debt amount for a customer.
     * Returns ZERO if not found (for calculation purposes).
     */
    public BigDecimal getDebtAmount(String customerId) {
        String normalizedId = TinValidator.normalize(customerId);

        return initialDebtRepository.findByCustomerId(normalizedId)
                .map(InitialDebtDto::getDebt)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Check if a customer has an initial debt entry.
     */
    public boolean hasInitialDebt(String customerId) {
        String normalizedId = TinValidator.normalize(customerId);
        return initialDebtRepository.findByCustomerId(normalizedId).isPresent();
    }

    /**
     * Add a new initial debt entry.
     */
    public InitialDebtDto addDebt(InitialDebtDto debtDto) {
        validateDebt(debtDto);

        String normalizedId = TinValidator.normalize(debtDto.getCustomerId());

        // Check for duplicate
        if (initialDebtRepository.findByCustomerId(normalizedId).isPresent()) {
            throw new DuplicateResourceException("Initial debt", normalizedId);
        }

        // Normalize the DTO
        InitialDebtDto normalized = InitialDebtDto.builder()
                .customerId(normalizedId)
                .name(debtDto.getName().trim())
                .debt(debtDto.getDebt())
                .date(debtDto.getDate())
                .build();

        log.info("Adding initial debt for customer: {} ({}), amount: {}",
                normalized.getName(), normalizedId, normalized.getDebt());

        return initialDebtRepository.save(normalized);
    }

    /**
     * Update an existing initial debt.
     */
    public InitialDebtDto updateDebt(String customerId, InitialDebtDto debtDto) {
        String normalizedId = TinValidator.normalize(customerId);

        // Verify exists
        if (initialDebtRepository.findByCustomerId(normalizedId).isEmpty()) {
            throw new ResourceNotFoundException("Initial debt", normalizedId);
        }

        validateDebt(debtDto);

        // Update with normalized values
        InitialDebtDto updated = InitialDebtDto.builder()
                .customerId(normalizedId)
                .name(debtDto.getName().trim())
                .debt(debtDto.getDebt())
                .date(debtDto.getDate())
                .build();

        log.info("Updating initial debt for customer: {} ({}), new amount: {}",
                updated.getName(), normalizedId, updated.getDebt());

        return initialDebtRepository.save(updated);
    }

    /**
     * Delete an initial debt entry.
     */
    public void deleteDebt(String customerId) {
        String normalizedId = TinValidator.normalize(customerId);

        // Verify exists
        if (initialDebtRepository.findByCustomerId(normalizedId).isEmpty()) {
            throw new ResourceNotFoundException("Initial debt", normalizedId);
        }

        log.info("Deleting initial debt for customer: {}", normalizedId);
        initialDebtRepository.delete(normalizedId);
    }

    /**
     * Bulk import initial debts.
     * Updates existing entries, creates new ones.
     */
    public List<InitialDebtDto> bulkImport(List<InitialDebtDto> debts) {
        log.info("Bulk importing {} initial debts", debts.size());

        List<InitialDebtDto> imported = new ArrayList<>();

        for (InitialDebtDto debt : debts) {
            try {
                validateDebt(debt);
                String normalizedId = TinValidator.normalize(debt.getCustomerId());

                InitialDebtDto normalized = InitialDebtDto.builder()
                        .customerId(normalizedId)
                        .name(debt.getName().trim())
                        .debt(debt.getDebt())
                        .date(debt.getDate())
                        .build();

                InitialDebtDto saved = initialDebtRepository.save(normalized);
                imported.add(saved);
            } catch (Exception e) {
                log.warn("Failed to import debt for customer {}: {}",
                        debt.getCustomerId(), e.getMessage());
            }
        }

        log.info("Successfully imported {} of {} initial debts", imported.size(), debts.size());
        return imported;
    }

    // Private helper methods

    private void validateDebt(InitialDebtDto debt) {
        if (debt.getCustomerId() == null || debt.getCustomerId().isBlank()) {
            throw new ValidationException("customerId", "Customer ID is required");
        }

        if (!TinValidator.isValid(debt.getCustomerId())) {
            throw new ValidationException("customerId",
                    "Invalid TIN format. Must be 9 or 11 digits");
        }

        if (debt.getName() == null || debt.getName().isBlank()) {
            throw new ValidationException("name", "Customer name is required");
        }

        if (debt.getDebt() == null) {
            throw new ValidationException("debt", "Debt amount is required");
        }

        if (debt.getDebt().compareTo(BigDecimal.ZERO) < 0) {
            throw new ValidationException("debt", "Debt amount cannot be negative");
        }

        if (debt.getDate() == null || !debt.getDate().matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw new ValidationException("date", "Date must be in YYYY-MM-DD format");
        }
    }
}
