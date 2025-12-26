package ge.tastyerp.payment.service;

import ge.tastyerp.common.dto.payment.ExcelUploadResponse;
import ge.tastyerp.common.dto.payment.ExcelUploadResponse.SkippedTransaction;
import ge.tastyerp.common.dto.payment.ExcelUploadResponse.TransactionDetail;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.exception.ValidationException;
import ge.tastyerp.common.util.AmountUtils;
import ge.tastyerp.common.util.DateUtils;
import ge.tastyerp.common.util.UniqueCodeGenerator;
import ge.tastyerp.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.*;

/**
 * Service for processing Excel bank statements.
 *
 * This implements the EXACT reconciliation logic from the legacy application:
 * - Column mapping: A=Date, B=Description, E=Amount, F=Balance, L=CustomerID
 * - uniqueCode: date|amountCents|customerId|balanceCents
 * - Deduplication against Firebase + on-the-fly Set
 * - Batch processing with 100 rows per batch
 *
 * ALL business logic for Excel processing is here.
 * Controllers only delegate to this service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExcelProcessingService {

    private final PaymentRepository paymentRepository;
    private final PaymentReconciliationService reconciliationService;
    private final AsyncAggregationService asyncAggregationService;

    private static final List<String> BANK_SOURCES = List.of("tbc", "bog");
    @Value("${business.payment-cutoff-date:2025-04-29}")
    private String paymentCutoffDate;

    @Value("${business.batch-size:100}")
    private int batchSize;

    // Excel column indices (matching legacy exactly)
    private static final int COL_DATE = 0;          // Column A
    private static final int COL_DESCRIPTION = 1;   // Column B
    private static final int COL_AMOUNT = 4;        // Column E
    private static final int COL_BALANCE = 5;       // Column F
    private static final int COL_CUSTOMER_ID = 11;  // Column L

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    /**
     * Process Excel bank statement upload.
     * This is the main entry point for Excel processing.
     */
    public ExcelUploadResponse processExcelUpload(MultipartFile file, String bank) {
        long startTime = System.currentTimeMillis();
        String requestId = generateRequestId();

        validateFile(file);
        validateBank(bank);

        log.info("[{}] 📥 Excel upload started - Bank: {}, File: {}, Size: {} bytes",
                requestId, bank, file.getOriginalFilename(), file.getSize());

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            log.info("[{}] ✅ Excel file opened successfully", requestId);

            // TBC bank uses second sheet, BOG uses first (legacy behavior)
            int sheetIndex = "tbc".equalsIgnoreCase(bank) ? 1 : 0;
            if (workbook.getNumberOfSheets() <= sheetIndex) {
                log.warn("[{}] ⚠️ Sheet index {} not found, falling back to sheet 0", requestId, sheetIndex);
                sheetIndex = 0; // Fallback to first sheet
            }

            Sheet sheet = workbook.getSheetAt(sheetIndex);
            log.info("[{}] 📊 Processing sheet: {} (index {}), rows: {}",
                    requestId, sheet.getSheetName(), sheetIndex, sheet.getLastRowNum());

            ExcelUploadResponse response = processSheet(sheet, bank, false, requestId);

            long duration = System.currentTimeMillis() - startTime;
            log.info("[{}] ✅ Excel upload completed in {}ms - Added: {}, Duplicates: {}, Skipped: {}",
                    requestId, duration,
                    response.getAddedCount(),
                    response.getDuplicateCount(),
                    response.getSkippedCount());

            return response;

        } catch (IOException e) {
            log.error("[{}] ❌ Error reading Excel file: {}", requestId, e.getMessage(), e);
            throw new ValidationException("file", "Failed to read Excel file: " + e.getMessage());
        } catch (Exception e) {
            log.error("[{}] ❌ Unexpected error during Excel processing: {}", requestId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * Generate a unique request ID for tracking.
     */
    private String generateRequestId() {
        return "REQ-" + System.currentTimeMillis() + "-" + (int)(Math.random() * 1000);
    }

    /**
     * Validate Excel file without saving (dry run).
     */
    public ExcelUploadResponse validateExcel(MultipartFile file, String bank) {
        String requestId = generateRequestId();

        validateFile(file);
        validateBank(bank);

        log.info("[{}] 🔍 Validating Excel file for bank: {}", requestId, bank);

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            int sheetIndex = "tbc".equalsIgnoreCase(bank) ? 1 : 0;
            if (workbook.getNumberOfSheets() <= sheetIndex) {
                sheetIndex = 0;
            }

            Sheet sheet = workbook.getSheetAt(sheetIndex);
            return processSheet(sheet, bank, true, requestId); // validateOnly = true

        } catch (IOException e) {
            log.error("[{}] ❌ Error reading Excel file: {}", requestId, e.getMessage());
            throw new ValidationException("file", "Failed to read Excel file: " + e.getMessage());
        }
    }

    /**
     * Process Excel sheet and reconcile payments.
     *
     * CRITICAL: This implements the exact legacy algorithm:
     * 1. Index ALL existing Firebase payments by uniqueCode
     * 2. For each Excel row: validate → build code → check duplicate → save if new
     * 3. On-the-fly Set prevents same-upload duplicates
     */
    private ExcelUploadResponse processSheet(Sheet sheet, String bank, boolean validateOnly, String requestId) {
        String normalizedBank = bank == null ? "" : bank.trim().toLowerCase(Locale.ROOT);
        LocalDate cutoffDate = DateUtils.parseDate(paymentCutoffDate);
        LocalDate windowStart = cutoffDate != null ? cutoffDate.plusDays(1) : null;

        // Initialize result tracking
        List<TransactionDetail> addedTransactions = new ArrayList<>();
        List<TransactionDetail> duplicateTransactions = new ArrayList<>();
        List<SkippedTransaction> skippedTransactions = new ArrayList<>();

        List<PaymentDto> pendingSaves = validateOnly ? List.of() : new ArrayList<>();

        BigDecimal excelTotalAll = BigDecimal.ZERO;
        BigDecimal excelTotalWindow = BigDecimal.ZERO;
        BigDecimal analyzedTotal = BigDecimal.ZERO;
        BigDecimal duplicateAmountInWindow = BigDecimal.ZERO;
        BigDecimal overlapSkippedAmountInWindow = BigDecimal.ZERO;

        int beforeWindowCount = 0;

        LocalDate skipUpToDate = null;
        if (!validateOnly && windowStart != null) {
            Map<LocalDate, Map<String, BigDecimal>> uploadAggregates = buildUploadAggregates(sheet, windowStart);
            LocalDate latestDbDate = paymentRepository.findLatestPaymentDateForSources(BANK_SOURCES, windowStart);
            if (latestDbDate != null) {
                Map<String, BigDecimal> uploadTotals = uploadAggregates.get(latestDbDate);
                if (uploadTotals != null && !uploadTotals.isEmpty()) {
                    Map<String, BigDecimal> dbTotals = paymentRepository.getBankPaymentAggregatesForDate(latestDbDate, BANK_SOURCES);
                    if (aggregatesMatch(dbTotals, uploadTotals)) {
                        skipUpToDate = latestDbDate;
                        log.info("[{}] Overlap match at {}. Skipping uploaded rows on/before this date.",
                                requestId, latestDbDate);
                    } else {
                        log.info("[{}] Overlap mismatch at {}. No prefix skipping applied.",
                                requestId, latestDbDate);
                    }
                } else {
                    log.info("[{}] No uploaded rows found for existing latest date {}. No prefix skipping applied.",
                            requestId, latestDbDate);
                }
            }
        }

        // Load existing unique codes from Firebase (O(1) lookup)
        log.info("[{}] 🔍 Loading existing payment codes from Firebase", requestId);
        Set<String> existingCodes = paymentRepository.getAllUniqueCodesAfterCutoff();
        log.info("[{}] ✅ Loaded {} existing unique codes from Firebase", requestId, existingCodes.size());

        // On-the-fly Set to prevent duplicates within same upload
        Set<String> processedCodes = new HashSet<>(existingCodes);

        // Process rows (skip header row 0)
        int totalRows = sheet.getLastRowNum();
        log.info("[{}] 📊 Processing {} rows from Excel sheet", requestId, totalRows);

        for (int rowIndex = 1; rowIndex <= totalRows; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;

            // Extract cell values
            BigDecimal amount = getCellAsDecimal(row, COL_AMOUNT);
            BigDecimal balance = getCellAsDecimal(row, COL_BALANCE);
            String customerId = getCellAsString(row, COL_CUSTOMER_ID);
            Object dateValue = getCellValue(row, COL_DATE);
            String description = getCellAsString(row, COL_DESCRIPTION);

            // Track ALL Column E amounts for validation (legacy behavior)
            if (AmountUtils.isPositive(amount)) {
                excelTotalAll = excelTotalAll.add(amount);
            }

            // SKIP: Amount <= 0
            if (!AmountUtils.isPositive(amount)) {
                logSkip(requestId, rowIndex + 1, "amount<=0",
                        amount, balance, customerId, dateValue, description);
                skippedTransactions.add(SkippedTransaction.builder()
                        .rowIndex(rowIndex + 1)
                        .customerId(customerId != null ? customerId : "N/A")
                        .amount(amount)
                        .date(dateValue != null ? dateValue.toString() : "N/A")
                        .reason("Payment amount <= 0")
                        .build());
                continue;
            }

            // SKIP: Missing customer ID
            if (customerId == null || customerId.isBlank()) {
                logSkip(requestId, rowIndex + 1, "missing_customer_id",
                        amount, balance, customerId, dateValue, description);
                skippedTransactions.add(SkippedTransaction.builder()
                        .rowIndex(rowIndex + 1)
                        .customerId("N/A")
                        .amount(amount)
                        .date(dateValue != null ? dateValue.toString() : "N/A")
                        .reason("Missing customer ID")
                        .build());
                continue;
            }

            // Parse date
            LocalDate date = DateUtils.parseDate(dateValue);
            if (date == null) {
                logSkip(requestId, rowIndex + 1, "invalid_date",
                        amount, balance, customerId, dateValue, description);
                skippedTransactions.add(SkippedTransaction.builder()
                        .rowIndex(rowIndex + 1)
                        .customerId(customerId.trim())
                        .amount(amount)
                        .date(dateValue != null ? dateValue.toString() : "N/A")
                        .reason("Invalid date format")
                        .build());
                continue;
            }

            String dateStr = DateUtils.formatDate(date);

            // Check if in payment window (after cutoff date)
            boolean inPaymentWindow = DateUtils.isAfterCutoff(dateStr, paymentCutoffDate);

            if (inPaymentWindow) {
                excelTotalWindow = excelTotalWindow.add(amount);
            } else {
                beforeWindowCount++;
            }

            if (inPaymentWindow && skipUpToDate != null &&
                    (date.isBefore(skipUpToDate) || date.isEqual(skipUpToDate))) {
                overlapSkippedAmountInWindow = overlapSkippedAmountInWindow.add(amount);
                skippedTransactions.add(SkippedTransaction.builder()
                        .rowIndex(rowIndex + 1)
                        .customerId(customerId.trim())
                        .amount(amount)
                        .date(dateStr)
                        .reason("Skipped (matched overlap window)")
                        .build());
                continue;
            }

            // Build unique code (EXACT legacy formula)
            String uniqueCode = UniqueCodeGenerator.buildUniqueCode(
                    dateStr,
                    amount,
                    customerId.trim(),
                    balance
            );

            TransactionDetail detail = TransactionDetail.builder()
                    .rowIndex(rowIndex + 1)
                    .customerId(customerId.trim())
                    .amount(AmountUtils.round(amount))
                    .balance(AmountUtils.round(balance))
                    .date(dateStr)
                    .uniqueCode(uniqueCode)
                    .build();

            // Check for duplicate
            if (processedCodes.contains(uniqueCode)) {
                detail.setStatus("Duplicate");
                duplicateTransactions.add(detail);
                if (inPaymentWindow) {
                    duplicateAmountInWindow = duplicateAmountInWindow.add(amount);
                }
                String duplicateReason = existingCodes.contains(uniqueCode)
                        ? "duplicate_existing_db"
                        : "duplicate_in_upload";
                logDecision(requestId, detail, inPaymentWindow, duplicateReason, description);
                continue;
            }

            // Add to processed codes (prevents same-upload duplicates)
            processedCodes.add(uniqueCode);

            if (inPaymentWindow) {
                detail.setStatus("Added");
                addedTransactions.add(detail);
                analyzedTotal = analyzedTotal.add(amount);
                logDecision(requestId, detail, true, validateOnly ? "validate_only" : "queued_for_save", description);

                // Save to Firebase (unless validate-only mode)
                if (!validateOnly) {
                    PaymentDto payment = PaymentDto.builder()
                            .uniqueCode(uniqueCode)
                            .customerId(customerId.trim())
                            .paymentDate(date)
                            .amount(AmountUtils.round(amount))
                            .balance(AmountUtils.round(balance))
                            .description(description)
                            .source(normalizedBank)
                            .isAfterCutoff(true)
                            .uploadedAt(LocalDateTime.now())
                            .excelRowIndex(rowIndex + 1)
                            .build();

                    pendingSaves.add(payment);
                    if (pendingSaves.size() >= batchSize) {
                        saveBatch(requestId, pendingSaves);
                        pendingSaves.clear();
                    }
                }
            } else {
                detail.setStatus("BeforeWindow");
                logDecision(requestId, detail, false, "before_cutoff", description);
            }
        }

        if (!validateOnly && !pendingSaves.isEmpty()) {
            log.info("[{}] 💾 Saving final batch of {} payments to Firebase", requestId, pendingSaves.size());
            saveBatch(requestId, pendingSaves);
            pendingSaves.clear();
        }

        String aggregationJobId = null;

        // Trigger ASYNC aggregation after successful Excel upload
        // This updates customer_debt_summary collection with latest data
        // Processing happens in background to prevent HTTP timeout errors
        if (!validateOnly && !addedTransactions.isEmpty()) {
            log.info("[{}] 🚀 Triggering ASYNC customer debt aggregation after Excel upload", requestId);
            try {
                aggregationJobId = asyncAggregationService.triggerAggregation("excel_upload");
                log.info("[{}] ✅ Async aggregation job created: {}. Upload response will be sent immediately.",
                        requestId, aggregationJobId);
            } catch (Exception e) {
                log.error("[{}] ⚠️ Failed to trigger async aggregation: {}. Excel upload succeeded, but aggregation must be triggered manually.",
                        requestId, e.getMessage(), e);
                // Don't fail the Excel upload if aggregation trigger fails
                // Aggregation can be triggered manually later
            }
        } else if (!validateOnly && addedTransactions.isEmpty()) {
            log.info("[{}] ℹ️ No new payments added, skipping aggregation", requestId);
        }

        // Calculate existing app total for this bank
        BigDecimal appTotal = paymentRepository.sumPaymentsBySource(normalizedBank);

        // VALIDATION: Compare payments in window (added + duplicates) vs Excel window total
        // This ensures all valid payments in the payment window were processed correctly
        BigDecimal expectedWindowTotal = analyzedTotal
                .add(duplicateAmountInWindow)
                .add(overlapSkippedAmountInWindow);
        BigDecimal difference = excelTotalWindow.subtract(expectedWindowTotal).abs();
        boolean validationPassed = difference.compareTo(BigDecimal.valueOf(0.01)) <= 0;

        if (!validationPassed) {
            log.warn("VALIDATION WARNING: Excel window total (₾{}) != Processed total (₾{} added + ₾{} duplicates). Difference: ₾{}",
                    excelTotalWindow, analyzedTotal, duplicateAmountInWindow, difference);
        } else {
            log.info("VALIDATION PASSED: All payments in window processed correctly");
        }

        // Build response
        String message = String.format("%d payments processed. %d added, %d duplicates, %d skipped.",
                addedTransactions.size() + duplicateTransactions.size() + skippedTransactions.size(),
                addedTransactions.size(),
                duplicateTransactions.size(),
                skippedTransactions.size());

        if (beforeWindowCount > 0) {
            message += String.format(" %d payments before %s (historical).", beforeWindowCount, paymentCutoffDate);
        }

        log.info("[{}] 📦 Building response with aggregation job ID: {}", requestId, aggregationJobId);

        return ExcelUploadResponse.builder()
                .success(true)
                .message(message)
                .aggregationJobId(aggregationJobId)  // NEW: Include job ID for frontend polling
                .excelTotalAll(AmountUtils.round(excelTotalAll))
                .excelTotalWindow(AmountUtils.round(excelTotalWindow))
                .analyzedTotal(AmountUtils.round(analyzedTotal))
                .appTotal(AmountUtils.round(appTotal))
                .totalRowsProcessed(totalRows)
                .addedCount(addedTransactions.size())
                .duplicateCount(duplicateTransactions.size())
                .skippedCount(skippedTransactions.size())
                .beforeWindowCount(beforeWindowCount)
                .validationPassed(validationPassed)
                .validationDifference(AmountUtils.round(difference))
                .addedTransactions(addedTransactions)
                .duplicateTransactions(duplicateTransactions)
                .skippedTransactions(skippedTransactions)
                .build();
    }

    // ==================== HELPER METHODS ====================

    private void saveBatch(String requestId, List<PaymentDto> batch) {
        try {
            paymentRepository.saveAll(batch);
            for (PaymentDto payment : batch) {
                logSaved(requestId, payment);
            }
        } catch (Exception e) {
            logBatchFailure(requestId, batch, e);
            throw e;
        }
    }

    private void logSaved(String requestId, PaymentDto payment) {
        log.info("[{}] saved payment: row={} customerId={} date={} amount={} balance={} uniqueCode={} source={}",
                requestId,
                payment.getExcelRowIndex(),
                payment.getCustomerId(),
                payment.getPaymentDate(),
                payment.getAmount(),
                payment.getBalance(),
                payment.getUniqueCode(),
                payment.getSource());
    }

    private void logBatchFailure(String requestId, List<PaymentDto> batch, Exception e) {
        log.error("[{}] failed to save batch of {} payments: {}", requestId, batch.size(), e.getMessage(), e);
        for (PaymentDto payment : batch) {
            log.error("[{}] unsaved payment: row={} customerId={} date={} amount={} balance={} uniqueCode={} source={} reason={}",
                    requestId,
                    payment.getExcelRowIndex(),
                    payment.getCustomerId(),
                    payment.getPaymentDate(),
                    payment.getAmount(),
                    payment.getBalance(),
                    payment.getUniqueCode(),
                    payment.getSource(),
                    e.getMessage());
        }
    }

    private void logDecision(String requestId, TransactionDetail detail, boolean inPaymentWindow, String decision, String description) {
        log.info("[{}] payment decision: row={} customerId={} date={} amount={} balance={} uniqueCode={} inWindow={} decision={} description={}",
                requestId,
                detail.getRowIndex(),
                detail.getCustomerId(),
                detail.getDate(),
                detail.getAmount(),
                detail.getBalance(),
                detail.getUniqueCode(),
                inPaymentWindow,
                decision,
                description);
    }

    private void logSkip(String requestId, int rowIndex, String reason, BigDecimal amount, BigDecimal balance,
                         String customerId, Object dateValue, String description) {
        log.info("[{}] payment skipped: row={} reason={} customerId={} date={} amount={} balance={} description={}",
                requestId,
                rowIndex,
                reason,
                customerId,
                dateValue,
                amount,
                balance,
                description);
    }

    private Map<LocalDate, Map<String, BigDecimal>> buildUploadAggregates(Sheet sheet, LocalDate windowStart) {
        Map<LocalDate, Map<String, BigDecimal>> aggregates = new HashMap<>();
        int totalRows = sheet.getLastRowNum();

        for (int rowIndex = 1; rowIndex <= totalRows; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;

            BigDecimal amount = getCellAsDecimal(row, COL_AMOUNT);
            if (!AmountUtils.isPositive(amount)) continue;

            String customerId = getCellAsString(row, COL_CUSTOMER_ID);
            if (customerId == null || customerId.isBlank()) continue;

            LocalDate date = DateUtils.parseDate(getCellValue(row, COL_DATE));
            if (date == null || date.isBefore(windowStart)) continue;

            String normalizedCustomer = customerId.trim();
            aggregates
                    .computeIfAbsent(date, key -> new HashMap<>())
                    .merge(normalizedCustomer, AmountUtils.round(amount), BigDecimal::add);
        }

        return aggregates;
    }

    private boolean aggregatesMatch(Map<String, BigDecimal> dbTotals, Map<String, BigDecimal> uploadTotals) {
        if (dbTotals == null) dbTotals = Map.of();
        if (uploadTotals == null) uploadTotals = Map.of();
        if (dbTotals.size() != uploadTotals.size()) {
            return false;
        }
        for (Map.Entry<String, BigDecimal> entry : dbTotals.entrySet()) {
            BigDecimal uploadAmount = uploadTotals.get(entry.getKey());
            if (uploadAmount == null) {
                return false;
            }
            BigDecimal diff = entry.getValue().subtract(uploadAmount).abs();
            if (diff.compareTo(BigDecimal.valueOf(0.01)) > 0) {
                return false;
            }
        }
        return true;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("file", "File is required");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ValidationException("file", "File size exceeds maximum (10MB)");
        }

        String filename = file.getOriginalFilename();
        String lower = filename != null ? filename.toLowerCase(Locale.ROOT) : null;
        if (lower == null || (!lower.endsWith(".xlsx") && !lower.endsWith(".xls"))) {
            throw new ValidationException("file", "File must be an Excel file (.xlsx or .xls)");
        }
    }

    private void validateBank(String bank) {
        if (bank == null || bank.isBlank()) {
            throw new ValidationException("bank", "Bank is required");
        }

        if (!bank.equalsIgnoreCase("tbc") && !bank.equalsIgnoreCase("bog")) {
            throw new ValidationException("bank", "Bank must be 'tbc' or 'bog'");
        }
    }

    private Object getCellValue(Row row, int colIndex) {
        Cell cell = row.getCell(colIndex);
        if (cell == null) return null;

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate();
                }
                yield cell.getNumericCellValue();
            }
            case BOOLEAN -> cell.getBooleanCellValue();
            case FORMULA -> {
                try {
                    yield cell.getNumericCellValue();
                } catch (Exception e) {
                    yield cell.getStringCellValue();
                }
            }
            default -> null;
        };
    }

    private String getCellAsString(Row row, int colIndex) {
        Object value = getCellValue(row, colIndex);
        if (value == null) return null;

        if (value instanceof Number) {
            // Handle numeric customer IDs
            return String.valueOf(((Number) value).longValue());
        }

        return value.toString().trim();
    }

    private BigDecimal getCellAsDecimal(Row row, int colIndex) {
        Object value = getCellValue(row, colIndex);
        return AmountUtils.parseAmount(value);
    }
}
