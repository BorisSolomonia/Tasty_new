package ge.tastyerp.payment.audit;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import ge.tastyerp.common.dto.audit.ProductMovementDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingDto;
import ge.tastyerp.common.dto.auditlayer.AuditMappingStatus;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceRowPageDto;
import ge.tastyerp.common.dto.auditlayer.AuditSourceType;
import ge.tastyerp.common.dto.waybill.WaybillType;
import ge.tastyerp.common.exception.ExternalServiceException;
import ge.tastyerp.common.util.FutureResults;
import ge.tastyerp.common.util.SimpleTtlCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads the immutable source rows the audit layer reasons about, and attaches
 * each row's audit-layer reading (BOR-89 §6, §11).
 *
 * <p>Two source families today:</p>
 * <ul>
 *   <li><b>BANK</b> — Firestore {@code bankTransactions}, written both by the
 *       Excel statement importer and by the TBC DBI API sync, so the audit layer
 *       sees one row shape regardless of how a row arrived.</li>
 *   <li><b>RS_GE</b> — document lines fetched from waybill-service. These are
 *       read over HTTP exactly as {@code AuditControlService} already does; no
 *       second copy of the waybill logic is introduced.</li>
 * </ul>
 *
 * <p>Nothing here writes to a source collection.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditSourceRowService {

    private static final String COLLECTION_BANK = "bankTransactions";

    /** Cap on rows returned to a browser in one drill-down response. */
    public static final int MAX_ROWS = 2000;

    private final Firestore firestore;
    private final AuditMappingService mappingService;
    private final AuditLayerRepository auditLayerRepository;

    /** Field name matches the bean name "internalRestTemplate" for by-name resolution. */
    private final RestTemplate internalRestTemplate;

    @Value("${waybill.service.url:http://waybill-service:8081}")
    private String waybillServiceUrl;

    /** TTL for the per-range document-movement cache (ms). Default 3 minutes. */
    @Value("${audit.layer-movements-cache-ttl-ms:180000}")
    private long movementsCacheTtlMs;

    /** Distinct date ranges kept at once; see the note at the construction site. */
    static final int MAX_CACHED_RANGES = 4;

    private volatile SimpleTtlCache<String, List<ProductMovementDto>> movementsCache;

    // ==================== bank rows ====================

    /**
     * Bank statement rows in the period. {@code date} is stored as an ISO string,
     * so a lexicographic range is also a chronological one.
     */
    public List<AuditSourceRowDto> loadBankRows(LocalDate startDate, LocalDate endDate,
                                                Map<String, AuditMappingDto> mappings) {
        return loadBankRows(startDate, endDate, mappings, null);
    }

    /**
     * @param resolver establishes identity for rows whose tax-code column is
     *                 blank. Null builds one from these rows alone, which is the
     *                 useful default: the statement teaches the names itself.
     */
    public List<AuditSourceRowDto> loadBankRows(LocalDate startDate, LocalDate endDate,
                                                Map<String, AuditMappingDto> mappings,
                                                AuditCounterpartyResolver resolver) {
        List<AuditSourceRowDto> rows = new ArrayList<>();
        // A store failure throws (503). It used to return an empty list, which
        // rendered as "no bank movement in this period" — the tidy-looking empty
        // cash flow that AuditFlowService's own warning calls the most dangerous
        // output this module can produce (BOR-81 B-3).
        QuerySnapshot snapshot = FutureResults.await(firestore.collection(COLLECTION_BANK)
                .whereGreaterThanOrEqualTo("date", startDate.toString())
                .whereLessThanOrEqualTo("date", endDate.toString())
                .get(), "load bank transactions " + startDate + ".." + endDate);
        for (QueryDocumentSnapshot doc : snapshot.getDocuments()) {
            rows.add(attach(AuditSourceRowDto.builder()
                    .sourceType(AuditSourceType.BANK)
                    .sourceRowId(doc.getId())
                    .date(parseDate(doc.getString("date")))
                    .direction(doc.getString("direction"))
                    .amount(decimal(doc.get("amount")))
                    .counterpartyName(doc.getString("counterparty"))
                    .counterpartyTin(doc.getString("counterpartyInn"))
                    .description(doc.getString("description"))
                    .additionalInformation(doc.getString("additionalInformation"))
                    .reference(doc.getString("reference"))
                    .transactionType(doc.getString("transactionType"))
                    .build(), mappings));
        }

        // Identity is resolved after the whole window is loaded, because the
        // rows that DO carry a tax code are what teach the ones that do not.
        AuditCounterpartyResolver effective = resolver != null ? resolver
                : AuditCounterpartyResolver.build(observedNameTins(rows),
                        auditLayerRepository.findCounterpartyAliases());
        for (AuditSourceRowDto row : rows) {
            AuditCounterpartyResolver.Resolution r =
                    effective.resolve(row.getCounterpartyTin(), row.getCounterpartyName());
            row.setResolvedCounterpartyTin(r.tin());
            row.setCounterpartyIdentitySource(r.source());
            row.setCounterpartyIdentityBasis(r.basis());
        }
        return rows;
    }

    /** The (name, tax code) pairs a set of rows can teach. */
    public static List<AuditCounterpartyResolver.NameTin> observedNameTins(List<AuditSourceRowDto> rows) {
        List<AuditCounterpartyResolver.NameTin> observed = new ArrayList<>();
        for (AuditSourceRowDto row : rows) {
            if (row.getCounterpartyName() != null && row.getCounterpartyTin() != null
                    && !row.getCounterpartyTin().isBlank()) {
                observed.add(new AuditCounterpartyResolver.NameTin(
                        row.getCounterpartyName(), row.getCounterpartyTin()));
            }
        }
        return observed;
    }

    // ==================== RS.ge document rows ====================

    /**
     * Identity of a document line, minus the duplicate discriminator.
     *
     * <p>RS.ge line items carry no id of their own, so identity is composed from
     * the fields that make a line unique. Quantity and amount are part of it:
     * a single waybill legitimately carries the same product on more than one
     * line (different weights or prices), and without them those lines would
     * share an id — one mapping would then silently claim both.</p>
     */
    private static String documentRowKey(ProductMovementDto movement) {
        return String.join("#",
                nullSafe(movement.getWaybillId()),
                nullSafe(movement.getType() == null ? null : movement.getType().name()),
                nullSafe(movement.getProductName()),
                nullSafe(movement.getDate() == null ? null : movement.getDate().toString()),
                plain(movement.getQuantityKg()),
                plain(movement.getAmount()));
    }

    /**
     * Full id for a document line: its identity plus an occurrence suffix.
     *
     * <p>The suffix only ever appears when a waybill really does repeat an
     * otherwise identical line. It is derived per-waybill, so re-syncing the same
     * waybill reproduces the same ids and existing mappings survive — which a
     * global positional index would not.</p>
     *
     * @param occurrence 0 for the first line with this key, 1 for the next, …
     */
    public static String documentRowId(ProductMovementDto movement, int occurrence) {
        String key = documentRowKey(movement);
        return occurrence == 0 ? key : key + "#" + occurrence;
    }

    /**
     * Movements for a range, cached briefly.
     *
     * <p>A single audit page load asks for the same range many times over: the
     * flows payload, the supplier registry, and every problem drill-down. Each
     * was a fresh HTTP round trip pulling tens of thousands of document lines,
     * which is why expanding a problem's evidence took over a minute. The window
     * is deliberately short — user-editable data is never cached, only the
     * immutable document feed.</p>
     */
    public List<ProductMovementDto> loadProductMovements(LocalDate startDate, LocalDate endDate) {
        return movementsCache().getOrCompute(startDate + "|" + endDate,
                () -> fetchProductMovements(startDate, endDate));
    }

    private SimpleTtlCache<String, List<ProductMovementDto>> movementsCache() {
        SimpleTtlCache<String, List<ProductMovementDto>> local = movementsCache;
        if (local == null) {
            synchronized (this) {
                if (movementsCache == null) {
                    // 4, not 16: each value is a full multi-year document feed and
                    // the working set is one range (BOR-90 finding M-3).
                    movementsCache = SimpleTtlCache.named("audit.movements", movementsCacheTtlMs, MAX_CACHED_RANGES);
                }
                local = movementsCache;
            }
        }
        return local;
    }

    private List<ProductMovementDto> fetchProductMovements(LocalDate startDate, LocalDate endDate) {
        try {
            String url = String.format("%s/api/waybills/product-movements?startDate=%s&endDate=%s",
                    waybillServiceUrl, startDate, endDate);
            @SuppressWarnings("unchecked")
            Map<String, Object> response = internalRestTemplate.getForObject(url, Map.class);
            if (response == null || !(response.get("data") instanceof List)) {
                return Collections.emptyList();
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> data = (List<Map<String, Object>>) response.get("data");
            List<ProductMovementDto> movements = new ArrayList<>();
            for (Map<String, Object> m : data) {
                LocalDate date = m.get("date") == null ? null : parseDate(String.valueOf(m.get("date")));
                if (date == null || date.isBefore(startDate) || date.isAfter(endDate)) {
                    continue;
                }
                movements.add(ProductMovementDto.builder()
                        .date(date)
                        .type(m.get("type") == null ? null : WaybillType.valueOf(String.valueOf(m.get("type"))))
                        .productName((String) m.get("productName"))
                        .parentCategory((String) m.get("parentCategory"))
                        .quantityKg(decimal(m.get("quantityKg")))
                        .unit((String) m.get("unit"))
                        .amount(decimal(m.get("amount")))
                        .waybillId((String) m.get("waybillId"))
                        .counterpartyId((String) m.get("counterpartyId"))
                        .build());
            }
            return movements;
        } catch (Exception e) {
            throw new ExternalServiceException("waybill-service", "fetch product movements", e);
        }
    }

    public List<AuditSourceRowDto> toDocumentRows(List<ProductMovementDto> movements,
                                                  Map<String, AuditMappingDto> mappings) {
        List<AuditSourceRowDto> rows = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        for (ProductMovementDto movement : movements) {
            int occurrence = seen.merge(documentRowKey(movement), 0, (a, b) -> a + 1);
            rows.add(attach(AuditSourceRowDto.builder()
                    .sourceType(AuditSourceType.RS_GE)
                    .sourceRowId(documentRowId(movement, occurrence))
                    .date(movement.getDate())
                    .direction(movement.getType() == null ? null : movement.getType().name())
                    .amount(movement.getAmount())
                    .quantityKg(movement.getQuantityKg())
                    .productName(movement.getProductName())
                    .counterpartyTin(movement.getCounterpartyId())
                    .description(movement.getProductName())
                    .reference(movement.getWaybillId())
                    .build(), mappings));
        }
        return rows;
    }

    // ==================== combined query ====================

    /**
     * The reconciliation queue: bank rows and document rows side by side, so one
     * screen can work through everything awaiting classification.
     *
     * @param sourceType null includes both families
     * @param status     null includes every status
     * @param search     matches counterparty, description or reference, case-insensitively
     */
    public AuditSourceRowPageDto query(LocalDate startDate, LocalDate endDate,
                                       AuditSourceType sourceType, AuditMappingStatus status,
                                       String search, int limit) {
        Map<String, AuditMappingDto> mappings = mappingService.loadMappingIndex();
        List<AuditSourceRowDto> rows = new ArrayList<>();
        if (sourceType == null || sourceType == AuditSourceType.BANK) {
            rows.addAll(loadBankRows(startDate, endDate, mappings));
        }
        if (sourceType == null || sourceType == AuditSourceType.RS_GE) {
            rows.addAll(toDocumentRows(loadProductMovements(startDate, endDate), mappings));
        }

        String needle = search == null || search.isBlank() ? null : search.trim().toLowerCase();
        List<AuditSourceRowDto> filtered = new ArrayList<>();
        for (AuditSourceRowDto row : rows) {
            if (status != null && row.getStatus() != status) {
                continue;
            }
            if (needle != null && !matches(row, needle)) {
                continue;
            }
            filtered.add(row);
        }
        // Unclassified work first, then most recent — the queue should open on
        // what still needs doing rather than on what is already settled.
        filtered.sort(Comparator
                .comparing((AuditSourceRowDto r) -> r.getStatus() == AuditMappingStatus.UNMAPPED ? 0 : 1)
                .thenComparing(r -> r.getDate() == null ? LocalDate.MIN : r.getDate(),
                        Comparator.reverseOrder()));

        int cap = limit > 0 ? Math.min(limit, MAX_ROWS) : MAX_ROWS;
        int total = filtered.size();
        List<AuditSourceRowDto> page = total > cap
                ? new ArrayList<>(filtered.subList(0, cap)) : filtered;

        // Report what the page actually covers. Sorting unclassified-first then
        // newest-first means a 3.5-year request can come back covering three
        // weeks, and a caller counting from it would read the silence as clean.
        LocalDate coverageFrom = null;
        LocalDate coverageTo = null;
        for (AuditSourceRowDto row : page) {
            if (row.getDate() == null) {
                continue;
            }
            if (coverageFrom == null || row.getDate().isBefore(coverageFrom)) {
                coverageFrom = row.getDate();
            }
            if (coverageTo == null || row.getDate().isAfter(coverageTo)) {
                coverageTo = row.getDate();
            }
        }
        if (total > cap) {
            log.info("Source-row query capped {} rows to {}; returned window {} → {}",
                    total, cap, coverageFrom, coverageTo);
        }

        return AuditSourceRowPageDto.builder()
                .rows(page)
                .totalCount(total)
                .returnedCount(page.size())
                .truncated(total > cap)
                .coverageFrom(coverageFrom)
                .coverageTo(coverageTo)
                .requestedFrom(startDate)
                .requestedTo(endDate)
                .build();
    }

    private static boolean matches(AuditSourceRowDto row, String needle) {
        return contains(row.getCounterpartyName(), needle)
                || contains(row.getCounterpartyTin(), needle)
                || contains(row.getDescription(), needle)
                || contains(row.getAdditionalInformation(), needle)
                || contains(row.getReference(), needle)
                || contains(row.getProductName(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase().contains(needle);
    }

    // ==================== shared ====================

    /** Attaches the row's mapping, status and derived unresolved remainder. */
    private AuditSourceRowDto attach(AuditSourceRowDto row, Map<String, AuditMappingDto> mappings) {
        AuditMappingDto mapping = mappings == null ? null
                : mappings.get(AuditLayerRepository.mappingId(row.getSourceType(), row.getSourceRowId()));
        row.setMapping(mapping);
        if (mapping == null || mapping.getStatus() == AuditMappingStatus.VOIDED) {
            row.setStatus(AuditMappingStatus.UNMAPPED);
            row.setUnresolvedAmount(abs(row.getAmount()));
        } else {
            row.setStatus(mapping.getStatus());
            row.setUnresolvedAmount(AuditMappingService.unresolvedOf(mapping));
        }
        return row;
    }

    /** Loads the full history for one row, for the terminal drill-down node. */
    public AuditSourceRowDto withHistory(AuditSourceRowDto row) {
        row.setHistory(mappingService.getHistory(row.getSourceType(), row.getSourceRowId()));
        return row;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    /** Scale-independent rendering, so 11.0 and 11.00 do not become different ids. */
    private static String plain(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }

    private static BigDecimal abs(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value.abs();
    }

    private static LocalDate parseDate(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value);
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
