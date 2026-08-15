package ge.tastyerp.payment.service;

import ge.tastyerp.common.dto.payment.ExcelUploadResponse;
import ge.tastyerp.common.dto.payment.PaymentDto;
import ge.tastyerp.payment.repository.ManualCashPaymentRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BOR-81 finding B-5 regression: re-uploading a manual-cash Excel file must not
 * double every receipt (which erased customer debt). Identity is
 * {@code date|amountCents|customerId} plus an in-file ordinal, so a legitimate
 * repeated receipt on one day survives while a re-upload is idempotent.
 */
class ManualCashExcelImportServiceTest {

    private final ManualCashPaymentRepository repository = mock(ManualCashPaymentRepository.class);
    private final ManualCashExcelImportService service = new ManualCashExcelImportService(repository);

    ManualCashExcelImportServiceTest() {
        ReflectionTestUtils.setField(service, "paymentCutoffDate", "2025-04-29");
        when(repository.saveAll(anyList())).thenAnswer(inv -> ((List<?>) inv.getArgument(0)).size());
    }

    /** Column A = date, C = amount, E = customer id (the service's documented layout). */
    private static MockMultipartFile workbook(Object[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("cash");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Date");
            header.createCell(2).setCellValue("Amount");
            header.createCell(4).setCellValue("Customer");
            for (int i = 0; i < rows.length; i++) {
                Row r = sheet.createRow(i + 1);
                r.createCell(0).setCellValue((String) rows[i][0]);
                r.createCell(2).setCellValue(((Number) rows[i][1]).doubleValue());
                r.createCell(4).setCellValue((String) rows[i][2]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return new MockMultipartFile("file", "cash.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        }
    }

    private static final Object[][] ROWS = {
            {"2025-06-01", 100.0, "204900358"},
            {"2025-06-01", 100.0, "204900358"},   // a genuine second receipt, same day/amount
            {"2025-06-02", 250.5, "402297787"},
    };

    @Test
    @DisplayName("First upload writes deterministic ids; the same-day repeat gets its own ordinal")
    @SuppressWarnings("unchecked")
    void firstUploadIsDeterministic() throws Exception {
        when(repository.findByDateAfter(any())).thenReturn(List.of());

        ExcelUploadResponse response = service.processManualExcelUpload(workbook(ROWS));

        assertEquals(3, response.getAddedCount());
        assertEquals(0, response.getDuplicateCount());
        ArgumentCaptor<List<PaymentDto>> written = ArgumentCaptor.forClass(List.class);
        verify(repository).saveAll(written.capture());
        verify(repository, never()).save(any());
        List<String> ids = written.getValue().stream().map(PaymentDto::getId).collect(Collectors.toList());
        assertEquals(List.of(
                "mcx|2025-06-01|10000|204900358|1",
                "mcx|2025-06-01|10000|204900358|2",
                "mcx|2025-06-02|25050|402297787|1"), ids);
        assertNotEquals(ids.get(0), ids.get(1), "two receipts in one file must not collapse into one");
        assertTrue(written.getValue().stream().allMatch(p -> p.getId().equals(p.getUniqueCode())));
    }

    @Test
    @DisplayName("Re-uploading the same file adds nothing and reports every row as a duplicate")
    void reuploadIsIdempotent() throws Exception {
        List<PaymentDto> alreadyStored = List.of(
                stored("2025-06-01", "100.00", "204900358"),
                stored("2025-06-01", "100.00", "204900358"),
                stored("2025-06-02", "250.50", "402297787"));
        when(repository.findByDateAfter(any())).thenReturn(alreadyStored);

        ExcelUploadResponse response = service.processManualExcelUpload(workbook(ROWS));

        assertEquals(0, response.getAddedCount());
        assertEquals(3, response.getDuplicateCount());
        assertEquals(3, response.getDuplicateTransactions().size());
        assertTrue(response.getMessage().contains("3 duplicates"), response.getMessage());
    }

    @Test
    @DisplayName("Legacy random-id rows count toward identity, so a third same-day receipt is still new")
    void ordinalBeyondStoredCountIsAdded() throws Exception {
        // Store holds ONE 100.00 receipt for the customer on that day (legacy import).
        when(repository.findByDateAfter(any())).thenReturn(List.of(stored("2025-06-01", "100.00", "204900358")));

        ExcelUploadResponse response = service.processManualExcelUpload(workbook(ROWS));

        // Row 1 (ordinal 1) is the stored one → duplicate; row 2 (ordinal 2) is new; row 3 is new.
        assertEquals(2, response.getAddedCount());
        assertEquals(1, response.getDuplicateCount());
    }

    private static PaymentDto stored(String date, String amount, String customerId) {
        return PaymentDto.builder()
                .id("legacy-" + Math.random())
                .customerId(customerId)
                .amount(new BigDecimal(amount))
                .paymentDate(LocalDate.parse(date))
                .source("manual-cash")
                .build();
    }
}
