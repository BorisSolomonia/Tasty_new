package ge.tastyerp.payment.bank.tbc;

import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.exception.ExternalServiceException;
import ge.tastyerp.common.exception.ValidationException;
import ge.tastyerp.common.util.DateUtils;
import ge.tastyerp.payment.bank.BankApiProperties;
import ge.tastyerp.payment.repository.PaymentRepository;
import ge.tastyerp.payment.service.DebtService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Continuous TBC DBI sync, modelled on the benchmark's scheduled source sync:
 * hourly fixed-delay schedule, incremental fetch from the last stored movement
 * date minus an overlap window (bank rows carry no time-of-day, so the overlap
 * plus window replacement keeps late same-day rows without dropping legit
 * duplicates), and a minimum-interval throttle shared by the scheduler and the
 * manual trigger.
 *
 * Every fetched movement lands in bankTransactions (incomes AND expenses, with
 * receiver TINs). CREDIT movements with a valid counterparty TIN additionally
 * become payments rows and join the customer debt rollup; credits without a
 * usable TIN are counted as unmatched in the sync state instead of being
 * silently excluded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TbcSyncService {

    private final BankApiProperties properties;
    private final TbcDbiClient tbcDbiClient;
    private final BankTransactionRepository bankTransactionRepository;
    private final PaymentRepository paymentRepository;
    private final DebtService debtService;

    @Value("${business.payment-cutoff-date:2025-04-29}")
    private String paymentCutoffDate;

    @Scheduled(
        fixedDelayString = "${bank-api.sync-interval-minutes:60}",
        initialDelay = 1,
        timeUnit = TimeUnit.MINUTES
    )
    public void scheduledSync() {
        if (!properties.getTbc().isEnabled()) {
            log.debug("TBC DBI sync skipped - integration disabled");
            return;
        }
        try {
            SyncResult result = syncNow(null, null, false);
            log.info("TBC scheduled sync finished: {}", result.message());
        } catch (Exception e) {
            log.error("TBC scheduled sync failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Run one sync cycle. Explicit from/to override the incremental window
     * (used by the manual endpoint for targeted backfills); force skips the
     * min-interval throttle.
     */
    public synchronized SyncResult syncNow(LocalDate fromOverride, LocalDate toOverride, boolean force) {
        BankApiProperties.Tbc config = properties.getTbc();
        if (!config.isEnabled()) {
            throw new ValidationException("TBC DBI integration is disabled. Set TBC_DBI_ENABLED=true and provide the TBC_DBI_* credentials in .env.");
        }

        Map<String, Object> state = bankTransactionRepository.getSyncState(TbcMovementMapper.SOURCE);
        LocalDateTime now = LocalDateTime.now();

        if (!force && fromOverride == null && isThrottled(state, now)) {
            return SyncResult.throttled(properties.getMinSyncIntervalSeconds());
        }

        LocalDate to = toOverride != null ? toOverride : LocalDate.now();
        LocalDate from = fromOverride != null ? fromOverride : incrementalFrom(state);
        if (from.isAfter(to)) {
            from = to;
        }

        try {
            List<BankTransaction> movements = tbcDbiClient.getAccountMovements(from, to);

            // Upsert under deterministic ids — never delete-and-reinsert. Window
            // replacement reassigned random ids each run and shared the Excel
            // mirror's source tag, so it orphaned human mappings and would have
            // deleted mirrored statement rows (BOR-81 findings B-1/B-2).
            Map<String, Map<String, Object>> rowsById = new java.util.LinkedHashMap<>();
            Map<String, Integer> seen = new HashMap<>();
            for (BankTransaction tx : movements) {
                int ordinal = seen.merge(TbcMovementMapper.bankRowKey(tx), 1, Integer::sum);
                rowsById.put(TbcMovementMapper.bankRowId(tx, ordinal), TbcMovementMapper.toBankTransactionMap(tx, now));
            }
            int storedBankRows = bankTransactionRepository.upsertAll(rowsById);

            LocalDate cutoff = DateUtils.parseDate(paymentCutoffDate);
            Set<String> existingCodes = paymentRepository.getAllUniqueCodesAfterCutoff();
            List<PaymentDto> newPayments = new ArrayList<>();
            int skippedExisting = 0;
            int unmatchedCredits = 0;
            for (BankTransaction tx : movements) {
                if (!tx.isIncome()) {
                    continue;
                }
                Optional<PaymentDto> payment = TbcMovementMapper.toPayment(tx, cutoff, now);
                if (payment.isEmpty()) {
                    unmatchedCredits++;
                    continue;
                }
                if (existingCodes.contains(payment.get().getUniqueCode())) {
                    skippedExisting++;
                    continue;
                }
                newPayments.add(payment.get());
            }
            if (!newPayments.isEmpty()) {
                paymentRepository.saveAll(newPayments);
                debtService.invalidate(); // debt is computed on demand — drop the cached snapshot
            }

            SyncResult result = new SyncResult(
                    from, to, movements.size(), storedBankRows,
                    newPayments.size(), skippedExisting, unmatchedCredits, false,
                    "Synced %d movements (%s → %s): %d new payments, %d already stored, %d unmatched credits."
                            .formatted(movements.size(), from, to, newPayments.size(), skippedExisting, unmatchedCredits)
            );
            bankTransactionRepository.saveSyncState(TbcMovementMapper.SOURCE, successState(state, result, movements, now));
            return result;
        } catch (Exception e) {
            bankTransactionRepository.saveSyncState(TbcMovementMapper.SOURCE, errorState(state, now, e));
            if (e instanceof TbcDbiException dbi) {
                throw new ExternalServiceException("TBC DBI", dbi.getMessage(), dbi);
            }
            if (e instanceof IllegalStateException) {
                // Client-side config validation (missing TBC_DBI_* keys) — an
                // operator problem, not a bank outage; surface the message.
                throw new ValidationException(e.getMessage());
            }
            throw e;
        }
    }

    /** Sync state plus effective configuration, for the status endpoint. */
    public Map<String, Object> status() {
        Map<String, Object> status = new HashMap<>();
        status.put("enabled", properties.getTbc().isEnabled());
        status.put("syncIntervalMinutes", properties.getSyncIntervalMinutes());
        status.put("syncOverlapDays", properties.getSyncOverlapDays());
        status.put("syncStartDate", incrementalStartDate().toString());
        status.put("state", bankTransactionRepository.getSyncState(TbcMovementMapper.SOURCE));
        return status;
    }

    public String changePassword(String otp, String newPassword, String currentPassword) {
        try {
            String message = tbcDbiClient.changePassword(otp, newPassword, currentPassword);
            return message + " IMPORTANT: update TBC_DBI_PASSWORD in .env now — the new password "
                    + "lives only in process memory and is lost on restart.";
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ValidationException(e.getMessage());
        } catch (TbcDbiException e) {
            throw new ExternalServiceException("TBC DBI", e.getMessage(), e);
        }
    }

    private boolean isThrottled(Map<String, Object> state, LocalDateTime now) {
        Object lastSyncedAt = state.get("lastSyncedAt");
        if (!(lastSyncedAt instanceof String value) || value.isBlank()) {
            return false;
        }
        try {
            LocalDateTime last = LocalDateTime.parse(value);
            return Duration.between(last, now).getSeconds() < properties.getMinSyncIntervalSeconds();
        } catch (Exception e) {
            return false;
        }
    }

    private LocalDate incrementalFrom(Map<String, Object> state) {
        Object lastMovementDate = state.get("lastMovementDate");
        if (lastMovementDate instanceof String value && !value.isBlank()) {
            try {
                return LocalDate.parse(value).minusDays(properties.getSyncOverlapDays());
            } catch (Exception e) {
                log.warn("Unparseable lastMovementDate '{}' in sync state - falling back to full backfill", value);
            }
        }
        return incrementalStartDate();
    }

    private LocalDate incrementalStartDate() {
        String configured = properties.getSyncStartDate();
        if (configured != null && !configured.isBlank()) {
            LocalDate parsed = DateUtils.parseDate(configured.trim());
            if (parsed != null) {
                return parsed;
            }
            log.warn("Unparseable BANK_SYNC_START_DATE '{}' - falling back to cutoff date", configured);
        }
        LocalDate cutoff = DateUtils.parseDate(paymentCutoffDate);
        return cutoff != null ? cutoff.plusDays(1) : LocalDate.now().minusMonths(1);
    }

    private Map<String, Object> successState(Map<String, Object> previous, SyncResult result,
                                             List<BankTransaction> movements, LocalDateTime now) {
        Map<String, Object> state = new HashMap<>(previous);
        state.put("lastSyncedAt", now.toString());
        state.put("lastStatus", "SUCCESS");
        state.remove("lastError");
        state.put("lastWindowFrom", result.from().toString());
        state.put("lastWindowTo", result.to().toString());
        state.put("lastFetchedCount", result.fetched());
        state.put("lastNewPayments", result.newPayments());
        state.put("lastUnmatchedCredits", result.unmatchedCredits());
        movements.stream()
                .map(BankTransaction::date)
                .max(LocalDate::compareTo)
                .ifPresent(latest -> {
                    Object previousLatest = previous.get("lastMovementDate");
                    if (!(previousLatest instanceof String value)
                            || value.isBlank()
                            || latest.isAfter(LocalDate.parse(value))) {
                        state.put("lastMovementDate", latest.toString());
                    }
                });
        if (!state.containsKey("coverageFrom")
                || result.from().toString().compareTo(String.valueOf(state.get("coverageFrom"))) < 0) {
            state.put("coverageFrom", result.from().toString());
        }
        return state;
    }

    private Map<String, Object> errorState(Map<String, Object> previous, LocalDateTime now, Exception e) {
        Map<String, Object> state = new HashMap<>(previous);
        state.put("lastSyncedAt", now.toString());
        state.put("lastStatus", "ERROR");
        state.put("lastError", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        return state;
    }

    public record SyncResult(
            LocalDate from,
            LocalDate to,
            int fetched,
            int storedBankRows,
            int newPayments,
            int skippedExisting,
            int unmatchedCredits,
            boolean throttled,
            String message
    ) {
        static SyncResult throttled(int minIntervalSeconds) {
            return new SyncResult(null, null, 0, 0, 0, 0, 0, true,
                    "Sync skipped - last sync was under " + minIntervalSeconds + "s ago. Use force=true to override.");
        }
    }
}
