package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.CheckEvidenceDto;
import ge.tastyerp.common.dto.auditlayer.CounterpartyAliasDto;
import ge.tastyerp.common.dto.auditlayer.RealInventoryOverrideDto;
import ge.tastyerp.common.dto.auditlayer.RealSupplierDebtDto;
import ge.tastyerp.common.exception.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * The manually maintained reality inputs (BOR-89 §12): confirmed real inventory,
 * real supplier debt, and payment evidence.
 *
 * <p>Every write goes through here so that no manual change can reach Firestore
 * without a change-log entry beside it. That is the only reason this is a service
 * and not direct repository access from the controller.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditOverrideService {

    private final AuditLayerRepository repository;
    private final AuditMappingService mappingService;

    // ==================== real inventory ====================

    public List<RealInventoryOverrideDto> getRealInventory() {
        return repository.findRealInventory();
    }

    /**
     * Confirms real on-hand stock for a product on a date. Real stock is normally
     * zero in this business, but "normally zero" is a fact someone asserts, not an
     * assumption the system may make silently — so a confirmation is stored and
     * logged even when the value is 0.
     */
    public RealInventoryOverrideDto saveRealInventory(RealInventoryOverrideDto dto, String operator) {
        requireOperator(operator);
        if (dto.getProductName() == null || dto.getProductName().isBlank()) {
            throw new ValidationException("productName", "Product name is required");
        }
        if (dto.getAsOfDate() == null) {
            throw new ValidationException("asOfDate", "An as-of date is required");
        }
        if (dto.getRealKg() == null) {
            throw new ValidationException("realKg", "Real stock in kg is required (0 is valid)");
        }
        if (dto.getRealKg().signum() < 0) {
            throw new ValidationException("realKg", "Real stock cannot be negative");
        }

        String id = dto.getProductName().trim() + "|" + dto.getAsOfDate();
        RealInventoryOverrideDto previous = getRealInventory().stream()
                .filter(o -> id.equals(o.getId()))
                .findFirst().orElse(null);

        dto.setId(id);
        dto.setUpdatedBy(operator);
        dto.setUpdatedAt(LocalDateTime.now());
        repository.saveRealInventory(dto);

        mappingService.log(operator, "REAL_INVENTORY", id, "realKg",
                previous == null ? null : String.valueOf(previous.getRealKg()),
                String.valueOf(dto.getRealKg()), dto.getNote());
        return dto;
    }

    /**
     * Withdraws a real-inventory confirmation entirely.
     *
     * <p>Distinct from confirming zero, and the distinction matters: "somebody
     * checked and there was nothing there" and "nobody has checked" are different
     * audit statements, and only the first should suppress a gap. Without this,
     * a confirmation entered in error — a typo, a test — could never be retracted,
     * only overwritten with another claim.</p>
     *
     * <p>The confirmation disappears; the change log entry recording that it was
     * withdrawn does not.</p>
     */
    public void deleteRealInventory(String id, String operator, String reason) {
        requireOperator(operator);
        RealInventoryOverrideDto previous = getRealInventory().stream()
                .filter(o -> id.equals(o.getId()))
                .findFirst().orElse(null);
        if (previous == null) {
            throw new ValidationException("id", "No real-inventory confirmation exists with id " + id);
        }
        repository.deleteRealInventory(id);
        mappingService.log(operator, "REAL_INVENTORY", id, "withdrawn",
                String.valueOf(previous.getRealKg()), null, reason);
    }

    // ==================== counterparty aliases ====================

    public List<CounterpartyAliasDto> getCounterpartyAliases() {
        return repository.findCounterpartyAliases();
    }

    /**
     * Teaches the audit layer that a counterparty name belongs to a tax code.
     *
     * <p>Needed because statements name counterparties without numbering them.
     * The link is stored as an overlay — the statement row keeps its blank, and
     * anything computed from the inferred identity says so.</p>
     */
    public CounterpartyAliasDto saveCounterpartyAlias(CounterpartyAliasDto dto, String operator) {
        requireOperator(operator);
        if (dto.getRawName() == null || dto.getRawName().isBlank()) {
            throw new ValidationException("rawName", "A counterparty name is required");
        }
        if (dto.getCounterpartyTin() == null || dto.getCounterpartyTin().isBlank()) {
            throw new ValidationException("counterpartyTin", "A tax code is required");
        }
        String normalized = AuditCounterpartyResolver.normalize(dto.getRawName());
        if (normalized == null) {
            throw new ValidationException("rawName", "That name normalises to nothing");
        }
        CounterpartyAliasDto previous = getCounterpartyAliases().stream()
                .filter(a -> normalized.equals(a.getNormalizedName()))
                .findFirst().orElse(null);

        dto.setId(normalized);
        dto.setNormalizedName(normalized);
        dto.setCounterpartyTin(dto.getCounterpartyTin().trim());
        dto.setCreatedBy(operator);
        dto.setCreatedAt(LocalDateTime.now());
        repository.saveCounterpartyAlias(dto);

        mappingService.log(operator, "COUNTERPARTY_ALIAS", normalized, "counterpartyTin",
                previous == null ? null : previous.getCounterpartyTin(),
                dto.getCounterpartyTin(), dto.getNote());
        return dto;
    }

    public void deleteCounterpartyAlias(String id, String operator, String reason) {
        requireOperator(operator);
        CounterpartyAliasDto previous = getCounterpartyAliases().stream()
                .filter(a -> id.equals(a.getId()))
                .findFirst().orElse(null);
        if (previous == null) {
            throw new ValidationException("id", "No counterparty alias exists with id " + id);
        }
        repository.deleteCounterpartyAlias(id);
        mappingService.log(operator, "COUNTERPARTY_ALIAS", id, "deleted",
                previous.getCounterpartyTin(), null, reason);
    }

    // ==================== supplier debt ====================

    public List<RealSupplierDebtDto> getSupplierDebts() {
        return repository.findSupplierDebts();
    }

    /**
     * Real outstanding debt is an audit <em>input</em>, deliberately not derived.
     * The ticket forbids inferring it from withdrawals, because a withdrawal does
     * not prove a supplier was settled.
     */
    public RealSupplierDebtDto saveSupplierDebt(RealSupplierDebtDto dto, String operator) {
        requireOperator(operator);
        boolean hasName = dto.getSupplierName() != null && !dto.getSupplierName().isBlank();
        boolean hasTin = dto.getSupplierTin() != null && !dto.getSupplierTin().isBlank();
        if (!hasName && !hasTin) {
            throw new ValidationException("supplierName", "A supplier name or TIN is required");
        }
        if (dto.getOutstandingAmount() == null) {
            throw new ValidationException("outstandingAmount", "An outstanding amount is required");
        }
        if (dto.getOutstandingAmount().signum() < 0) {
            throw new ValidationException("outstandingAmount", "Outstanding debt cannot be negative");
        }

        String id = hasTin ? dto.getSupplierTin().trim() : dto.getSupplierName().trim();
        RealSupplierDebtDto previous = getSupplierDebts().stream()
                .filter(d -> id.equals(d.getId()))
                .findFirst().orElse(null);

        dto.setId(id);
        dto.setUpdatedBy(operator);
        dto.setUpdatedAt(LocalDateTime.now());
        repository.saveSupplierDebt(dto);

        mappingService.log(operator, "SUPPLIER_DEBT", id, "outstandingAmount",
                previous == null ? null : String.valueOf(previous.getOutstandingAmount()),
                String.valueOf(dto.getOutstandingAmount()), dto.getNote());
        return dto;
    }

    // ==================== check evidence ====================

    public List<CheckEvidenceDto> getCheckEvidence(LocalDate startDate, LocalDate endDate) {
        List<CheckEvidenceDto> all = repository.findCheckEvidence();
        if (startDate == null || endDate == null) {
            return all;
        }
        return all.stream()
                .filter(c -> c.getDate() == null
                        || (!c.getDate().isBefore(startDate) && !c.getDate().isAfter(endDate)))
                .toList();
    }

    /**
     * Records payment evidence. {@code supportedAmount} is never accepted from the
     * caller — it is derived from the bank rows linked to this check, so evidence
     * can never mark itself as backed by money.
     */
    public CheckEvidenceDto saveCheckEvidence(CheckEvidenceDto dto, String operator) {
        requireOperator(operator);
        if (dto.getAmount() == null || dto.getAmount().signum() <= 0) {
            throw new ValidationException("amount", "A positive evidence amount is required");
        }
        boolean hasCounterparty = (dto.getCounterpartyName() != null && !dto.getCounterpartyName().isBlank())
                || (dto.getCounterpartyTin() != null && !dto.getCounterpartyTin().isBlank());
        if (!hasCounterparty) {
            throw new ValidationException("counterpartyName", "A counterparty name or TIN is required");
        }

        String id = dto.getId() == null || dto.getId().isBlank()
                ? buildCheckId(dto) : dto.getId();
        CheckEvidenceDto previous = repository.findCheckEvidence().stream()
                .filter(c -> id.equals(c.getId()))
                .findFirst().orElse(null);

        dto.setId(id);
        dto.setSupportedAmount(null);
        dto.setUnsupportedAmount(null);
        dto.setUpdatedBy(operator);
        dto.setUpdatedAt(LocalDateTime.now());
        repository.saveCheckEvidence(dto);

        mappingService.log(operator, "CHECK_EVIDENCE", id, "amount",
                previous == null ? null : String.valueOf(previous.getAmount()),
                String.valueOf(dto.getAmount()), dto.getNote());
        return dto;
    }

    public void deleteCheckEvidence(String id, String operator, String reason) {
        requireOperator(operator);
        CheckEvidenceDto previous = repository.findCheckEvidence().stream()
                .filter(c -> id.equals(c.getId()))
                .findFirst().orElse(null);
        if (previous == null) {
            throw new ValidationException("id", "No payment evidence exists with id " + id);
        }
        repository.deleteCheckEvidence(id);
        mappingService.log(operator, "CHECK_EVIDENCE", id, "deleted",
                String.valueOf(previous.getAmount()), null, reason);
    }

    private static String buildCheckId(CheckEvidenceDto dto) {
        String counterparty = dto.getCounterpartyTin() != null && !dto.getCounterpartyTin().isBlank()
                ? dto.getCounterpartyTin() : dto.getCounterpartyName();
        BigDecimal amount = dto.getAmount();
        return String.join("|",
                counterparty == null ? "" : counterparty.trim(),
                dto.getDocumentNumber() == null ? "" : dto.getDocumentNumber().trim(),
                dto.getDate() == null ? "" : dto.getDate().toString(),
                amount.stripTrailingZeros().toPlainString());
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new ValidationException("operator",
                    "An operator name is required so the change can be logged");
        }
    }
}
