package ge.tastyerp.payment.service;

import ge.tastyerp.common.dto.payment.CustomerDebtDto;
import ge.tastyerp.common.dto.payment.DebtOverviewDto;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.exception.ExternalServiceException;
import ge.tastyerp.common.util.SimpleTtlCache;
import ge.tastyerp.common.util.TinValidator;
import ge.tastyerp.payment.repository.ManualCashPaymentRepository;
import ge.tastyerp.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * Single source of truth for customer debt (BOR: device-consistency fix).
 *
 * Reproduces the legacy "correct reference" (payments-page) formula EXACTLY —
 *   currentDebt = startingDebt + totalSales(after-cutoff SALE) - totalPayments(all sources, >= paymentWindowStart)
 * — but computes it ONCE, server-side, in BigDecimal, keyed by
 * {@link TinValidator#canonicalId} so RS.ge's leading-zero-stripped IDs merge
 * with Excel/initial-debt IDs. Every page reads this; no client recomputes.
 *
 * The whole computation is wrapped in a short TTL cache so concurrent loads
 * from different devices share ONE identical snapshot (that is what makes the
 * numbers device-consistent), while bounding Firestore reads.
 *
 * Only immutable-in-practice / freshly-read inputs are used; the shared exclude
 * set is read fresh so edits take effect on the next TTL tick.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DebtService {

    private final PaymentRepository paymentRepository;
    private final ManualCashPaymentRepository manualCashPaymentRepository;
    private final RestTemplate restTemplate;

    @Value("${business.cutoff-date:2025-04-29}")
    private String cutoffDateString;

    @Value("${waybill.service.url:http://waybill-service:8081}")
    private String waybillServiceUrl;

    @Value("${config.service.url:http://config-service:8888}")
    private String configServiceUrl;

    @Value("${debt.cache-ttl-ms:60000}")
    private long cacheTtlMs;

    private volatile SimpleTtlCache<String, DebtOverviewDto> cache;

    private SimpleTtlCache<String, DebtOverviewDto> cache() {
        SimpleTtlCache<String, DebtOverviewDto> local = cache;
        if (local == null) {
            synchronized (this) {
                if (cache == null) {
                    cache = new SimpleTtlCache<>(cacheTtlMs, 2);
                }
                local = cache;
            }
        }
        return local;
    }

    /** Authoritative debt overview, cached for a short window for cross-device consistency. */
    public DebtOverviewDto getOverview() {
        return cache().getOrCompute("all", this::computeFresh);
    }

    /** Invalidate the cache (e.g. after the exclude set changes) so the next read is fresh. */
    public void invalidate() {
        cache().invalidateAll();
    }

    private DebtOverviewDto computeFresh() {
        long t0 = System.currentTimeMillis();
        LocalDate cutoff = LocalDate.parse(cutoffDateString);
        LocalDate paymentWindowStart = cutoff.plusDays(1);

        List<DebtInput> inputs = new ArrayList<>();
        inputs.addAll(fetchInitialDebts());
        inputs.addAll(fetchSales());
        for (PaymentDto p : paymentRepository.findByDateAfter(paymentWindowStart)) {
            inputs.add(new DebtInput(p.getCustomerId(), null, p.getAmount(), Kind.BANK_PAYMENT));
        }
        for (PaymentDto p : manualCashPaymentRepository.findByDateAfter(paymentWindowStart)) {
            inputs.add(new DebtInput(p.getCustomerId(), null, p.getAmount(), Kind.CASH_PAYMENT));
        }

        DebtOverviewDto overview = aggregate(inputs, fetchExcluded());
        log.info("Debt overview computed: {} customers, {} inputs, {} ms",
                overview.getCustomers().size(), inputs.size(), System.currentTimeMillis() - t0);
        return overview;
    }

    // ==================== PURE AGGREGATION (parity-tested) ====================

    public enum Kind { INITIAL, SALE, BANK_PAYMENT, CASH_PAYMENT }

    /** One raw transaction feeding the debt calculation. */
    public record DebtInput(String customerId, String name, BigDecimal amount, Kind kind) {}

    private static final class Acc {
        BigDecimal startingDebt = BigDecimal.ZERO;
        BigDecimal totalSales = BigDecimal.ZERO;
        BigDecimal totalBank = BigDecimal.ZERO;
        BigDecimal totalCash = BigDecimal.ZERO;
        int waybillCount = 0;
        int paymentCount = 0;
        String salesName;
        String initialName;
    }

    /**
     * Pure, deterministic aggregation — the byte-exact reference the parity test
     * pins. Groups by canonical id; sums in BigDecimal; totals are computed over
     * non-excluded customers from UNROUNDED sums (matching the reference), then
     * rounded once to 2dp. Per-customer fields are rounded to 2dp for display.
     */
    static DebtOverviewDto aggregate(List<DebtInput> inputs, Collection<String> excludedIds) {
        Set<String> excluded = new HashSet<>();
        for (String id : excludedIds) {
            excluded.add(TinValidator.canonicalId(id));
        }

        // TreeMap → deterministic customer ordering regardless of input order.
        Map<String, Acc> byCustomer = new TreeMap<>();
        for (DebtInput in : inputs) {
            if (in.amount() == null) continue;
            String key = TinValidator.canonicalId(in.customerId());
            Acc acc = byCustomer.computeIfAbsent(key, k -> new Acc());
            switch (in.kind()) {
                case INITIAL -> {
                    acc.startingDebt = acc.startingDebt.add(in.amount());
                    if (acc.initialName == null && isName(in.name())) acc.initialName = in.name();
                }
                case SALE -> {
                    acc.totalSales = acc.totalSales.add(in.amount());
                    acc.waybillCount++;
                    if (acc.salesName == null && isName(in.name())) acc.salesName = in.name();
                }
                case BANK_PAYMENT -> {
                    acc.totalBank = acc.totalBank.add(in.amount());
                    acc.paymentCount++;
                }
                case CASH_PAYMENT -> {
                    acc.totalCash = acc.totalCash.add(in.amount());
                    acc.paymentCount++;
                }
            }
        }

        List<CustomerDebtDto> customers = new ArrayList<>(byCustomer.size());
        BigDecimal tStarting = BigDecimal.ZERO, tSales = BigDecimal.ZERO,
                tPayments = BigDecimal.ZERO, tCash = BigDecimal.ZERO;

        for (Map.Entry<String, Acc> e : byCustomer.entrySet()) {
            String id = e.getKey();
            Acc a = e.getValue();
            BigDecimal totalPayments = a.totalBank.add(a.totalCash);
            BigDecimal currentDebt = a.startingDebt.add(a.totalSales).subtract(totalPayments);
            boolean isExcluded = excluded.contains(id);

            String name = a.salesName != null ? a.salesName
                    : a.initialName != null ? a.initialName : id;

            customers.add(CustomerDebtDto.builder()
                    .customerId(id)
                    .customerName(name)
                    .startingDebt(round(a.startingDebt))
                    .totalSales(round(a.totalSales))
                    .totalPayments(round(totalPayments))
                    .totalCashPayments(round(a.totalCash))
                    .currentDebt(round(currentDebt))
                    .waybillCount(a.waybillCount)
                    .paymentCount(a.paymentCount)
                    .excluded(isExcluded)
                    .build());

            if (!isExcluded) {
                tStarting = tStarting.add(a.startingDebt);
                tSales = tSales.add(a.totalSales);
                tPayments = tPayments.add(totalPayments);
                tCash = tCash.add(a.totalCash);
            }
        }

        return DebtOverviewDto.builder()
                .customers(customers)
                .totalSales(round(tSales))
                .totalPayments(round(tPayments))
                .totalCashPayments(round(tCash))
                .totalOutstanding(round(tStarting.add(tSales).subtract(tPayments)))
                .build();
    }

    private static boolean isName(String s) {
        return s != null && !s.isBlank();
    }

    private static BigDecimal round(BigDecimal v) {
        return (v != null ? v : BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
    }

    // ==================== INPUT FETCHERS ====================

    @SuppressWarnings("unchecked")
    private List<DebtInput> fetchInitialDebts() {
        try {
            String url = configServiceUrl + "/api/config/debts";
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            List<DebtInput> out = new ArrayList<>();
            if (response != null && response.get("data") instanceof List) {
                for (Map<String, Object> d : (List<Map<String, Object>>) response.get("data")) {
                    Object id = d.get("customerId");
                    if (id == null) continue;
                    BigDecimal amount = new BigDecimal(String.valueOf(d.getOrDefault("debt", "0")));
                    out.add(new DebtInput(String.valueOf(id), (String) d.get("name"), amount, Kind.INITIAL));
                }
            }
            return out;
        } catch (Exception e) {
            throw new ExternalServiceException("config-service", "fetch initial debts", e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<DebtInput> fetchSales() {
        try {
            // Same source the legacy payments-page used: after-cutoff SALE waybills.
            String url = waybillServiceUrl + "/api/waybills?afterCutoffOnly=true&type=SALE";
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            List<DebtInput> out = new ArrayList<>();
            if (response != null && response.get("data") instanceof List) {
                for (Map<String, Object> wb : (List<Map<String, Object>>) response.get("data")) {
                    Object id = wb.get("customerId");
                    if (id == null) continue;
                    BigDecimal amount = new BigDecimal(String.valueOf(wb.getOrDefault("amount", "0")));
                    out.add(new DebtInput(String.valueOf(id), (String) wb.get("customerName"), amount, Kind.SALE));
                }
            }
            return out;
        } catch (Exception e) {
            throw new ExternalServiceException("waybill-service", "fetch sales", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Set<String> fetchExcluded() {
        try {
            String url = configServiceUrl + "/api/config/excluded-customers";
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            Set<String> out = new HashSet<>();
            if (response != null && response.get("data") instanceof List) {
                for (Object id : (List<Object>) response.get("data")) {
                    if (id != null) out.add(String.valueOf(id));
                }
            }
            return out;
        } catch (Exception e) {
            // Exclusions are a refinement of the TOTAL, not the per-customer debt.
            // If config is briefly unreachable, show all customers rather than fail.
            log.warn("Could not fetch excluded customers, showing all: {}", e.getMessage());
            return Set.of();
        }
    }
}
