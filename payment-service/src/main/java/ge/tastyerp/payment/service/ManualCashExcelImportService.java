package ge.tastyerp.payment.service;

import ge.tastyerp.common.dto.payment.ExcelUploadResponse;
import ge.tastyerp.common.dto.payment.ExcelUploadResponse.SkippedTransaction;
import ge.tastyerp.common.dto.payment.ExcelUploadResponse.TransactionDetail;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.common.exception.ValidationException;
import ge.tastyerp.common.util.AmountUtils;
import ge.tastyerp.common.util.DateUtils;
import ge.tastyerp.payment.repository.ManualCashPaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Service for importing manual cash payments from Excel files.
 * Format: Column A = Date, Column C = Amount, Column E = Customer ID.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ManualCashExcelImportService {

    private static final int COL_DATE = 0;       // Column A
    private static final int COL_AMOUNT = 2;     // Column C
    private static final int COL_CUSTOMER_ID = 4; // Column E

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024; // 10MB

    private final ManualCashPaymentRepository manualCashPaymentRepository;

    @Value("${business.payment-cutoff-date:2025-04-29}")
    private String paymentCutoffDate;

    public ExcelUploadResponse processManualExcelUpload(MultipartFile file) {
        long startTime = System.currentTimeMillis();
        String requestId = generateRequestId();

        validateFile(file);

        log.info("[{}] Manual cash Excel upload started - File: {}, Size: {} bytes",
                requestId, file.getOriginalFilename(), file.getSize());

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            ExcelUploadResponse response = processSheet(sheet, requestId);

            long duration = System.currentTimeMillis() - startTime;
            log.info("[{}] Manual cash Excel upload completed in {}ms - Added: {}, Skipped: {}",
                    requestId, duration, response.getAddedCount(), response.getSkippedCount());

            return response;
        } catch (IOException e) {
            log.error("[{}] Error reading Excel file: {}", requestId, e.getMessage(), e);
            throw new ValidationException("file", "Failed to read Excel file: " + e.getMessage());
        } catch (Exception e) {
            log.error("[{}] Unexpected error during manual Excel processing: {}", requestId, e.getMessage(), e);
            throw e;
        }
    }

    private ExcelUploadResponse processSheet(Sheet sheet, String requestId) {
        List<TransactionDetail> addedTransactions = new ArrayList<>();
        List<SkippedTransaction> skippedTransactions = new ArrayList<>();

        BigDecimal excelTotalAll = BigDecimal.ZERO;
        BigDecimal excelTotalWindow = BigDecimal.ZERO;
        BigDecimal analyzedTotal = BigDecimal.ZERO;

        int beforeWindowCount = 0;

        int totalRows = sheet.getLastRowNum();
        log.info("[{}] Processing {} rows from manual Excel sheet", requestId, totalRows);

        for (int rowIndex = 1; rowIndex <= totalRows; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) continue;

            BigDecimal amount = getCellAsDecimal(row, COL_AMOUNT);
            String customerId = getCellAsString(row, COL_CUSTOMER_ID);
            Object dateValue = getCellValue(row, COL_DATE);

            if (AmountUtils.isPositive(amount)) {
                excelTotalAll = excelTotalAll.add(amount);
            }

            if (!AmountUtils.isPositive(amount)) {
                skippedTransactions.add(SkippedTransaction.builder()
                        .rowIndex(rowIndex + 1)
                        .customerId(customerId != null ? customerId : "N/A")
                        .amount(amount)
                        .date(dateValue != null ? dateValue.toString() : "N/A")
                        .reason("Payment amount <= 0")
                        .build());
                continue;
            }

            if (customerId == null || customerId.isBlank()) {
                skippedTransactions.add(SkippedTransaction.builder()
                        .rowIndex(rowIndex + 1)
                        .customerId("N/A")
                        .amount(amount)
                        .date(dateValue != null ? dateValue.toString() : "N/A")
                        .reason("Missing customer ID")
                        .build());
                continue;
            }

            LocalDate date = DateUtils.parseDate(dateValue);
            if (date == null) {
                skippedTransactions.add(SkippedTransaction.builder()
                        .rowIndex(rowIndex + 1)
                        .customerId(customerId.trim())
                        .amount(amount)
                        .date(dateValue != null ? dateValue.toString() : "N/A")
                        .reason("Invalid date format")
                        .build());
                continue;
            }

            if (!DateUtils.isAfterCutoff(DateUtils.formatDate(date), paymentCutoffDate)) {
                beforeWindowCount++;
                skippedTransactions.add(SkippedTransaction.builder()
                        .rowIndex(rowIndex + 1)
                        .customerId(customerId.trim())
                        .amount(amount)
                        .date(DateUtils.formatDate(date))
                        .reason("Before cutoff date")
                        .build());
                continue;
            }

            excelTotalWindow = excelTotalWindow.add(amount);
            analyzedTotal = analyzedTotal.add(amount);

            PaymentDto payment = PaymentDto.builder()
                    .customerId(customerId.trim())
                    .amount(AmountUtils.round(amount))
                    .paymentDate(date)
                    .description("Manual cash (Excel)")
                    .source("manual-cash")
                    .uploadedAt(LocalDateTime.now())
                    .build();

            manualCashPaymentRepository.save(payment);

            addedTransactions.add(TransactionDetail.builder()
                    .rowIndex(rowIndex + 1)
                    .customerId(customerId.trim())
                    .amount(AmountUtils.round(amount))
                    .date(DateUtils.formatDate(date))
                    .status("Added")
                    .build());
        }

        String message = String.format("%d payments processed. %d added, %d skipped.",
                addedTransactions.size() + skippedTransactions.size(),
                addedTransactions.size(),
                skippedTransactions.size());

        if (beforeWindowCount > 0) {
            message += String.format(" %d payments before %s (historical).", beforeWindowCount, paymentCutoffDate);
        }

        return ExcelUploadResponse.builder()
                .success(true)
                .message(message)
                .excelTotalAll(AmountUtils.round(excelTotalAll))
                .excelTotalWindow(AmountUtils.round(excelTotalWindow))
                .analyzedTotal(AmountUtils.round(analyzedTotal))
                .appTotal(BigDecimal.ZERO)
                .totalRowsProcessed(totalRows)
                .addedCount(addedTransactions.size())
                .duplicateCount(0)
                .skippedCount(skippedTransactions.size())
                .beforeWindowCount(beforeWindowCount)
                .validationPassed(true)
                .validationDifference(BigDecimal.ZERO)
                .addedTransactions(addedTransactions)
                .duplicateTransactions(List.of())
                .skippedTransactions(skippedTransactions)
                .build();
    }

    private String generateRequestId() {
        return "REQ-" + System.currentTimeMillis() + "-" + (int) (Math.random() * 1000);
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
            return String.valueOf(((Number) value).longValue());
        }

        return value.toString().trim();
    }

    private BigDecimal getCellAsDecimal(Row row, int colIndex) {
        Object value = getCellValue(row, colIndex);
        return AmountUtils.parseAmount(value);
    }
}
