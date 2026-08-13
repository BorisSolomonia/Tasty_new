package ge.tastyerp.payment.audit;

import ge.tastyerp.payment.bank.tbc.BankTransactionRepository;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The regression that matters most for BOR-89: before this service existed the
 * importer read column E ("Paid In") only, so bank outflows had never entered
 * the system and the whole cash flow had no data.
 *
 * <p>The fixture reproduces the real Bank of Georgia export layout verified from
 * {@code example/example.xlsx}: a Georgian header row, an English header row,
 * then data — with direction carried by which of columns D/E is populated rather
 * than by a sign.</p>
 */
class BankStatementMirrorServiceTest {

    private final BankTransactionRepository repository = mock(BankTransactionRepository.class);
    private final BankStatementMirrorService service = new BankStatementMirrorService(repository);

    @Test
    @SuppressWarnings("unchecked")
    void paidOutColumnBecomesAnOutflow() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = buildStatement(workbook);
            when(repository.upsertAll(anyMap())).thenReturn(2);

            service.mirror(sheet, "bog");

            ArgumentCaptor<Map<String, Map<String, Object>>> captor =
                    ArgumentCaptor.forClass(Map.class);
            verify(repository).upsertAll(captor.capture());
            Map<String, Map<String, Object>> written = captor.getValue();

            assertEquals(2, written.size(),
                    "both data rows mirror; the two header rows are skipped as undated");

            Map<String, Object> outflow = findByReference(written, "TX-OUT-1");
            assertNotNull(outflow, "the Paid Out row must be mirrored — it never was before");
            assertEquals("DEBIT", outflow.get("direction"),
                    "a populated Paid Out column means money left the account");
            assertEquals(12850.0, (Double) outflow.get("amount"), 0.001);
            assertEquals(Boolean.TRUE, outflow.get("isExpense"));
            assertEquals("405135946", outflow.get("counterpartyInn"),
                    "partner tax code comes from column L");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void paidInColumnStillBecomesAnInflow() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = buildStatement(workbook);
            when(repository.upsertAll(anyMap())).thenReturn(2);

            service.mirror(sheet, "bog");

            ArgumentCaptor<Map<String, Map<String, Object>>> captor =
                    ArgumentCaptor.forClass(Map.class);
            verify(repository).upsertAll(captor.capture());

            Map<String, Object> inflow = findByReference(captor.getValue(), "TX-IN-1");
            assertNotNull(inflow, "money-in rows must keep working");
            assertEquals("CREDIT", inflow.get("direction"));
            assertEquals(1150.0, (Double) inflow.get("amount"), 0.001);
            assertEquals(Boolean.TRUE, inflow.get("isIncome"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void reImportingTheSameFileReusesTheSameDocumentIds() throws Exception {
        try (Workbook first = new XSSFWorkbook(); Workbook second = new XSSFWorkbook()) {
            when(repository.upsertAll(anyMap())).thenReturn(2);

            service.mirror(buildStatement(first), "bog");
            service.mirror(buildStatement(second), "bog");

            ArgumentCaptor<Map<String, Map<String, Object>>> captor =
                    ArgumentCaptor.forClass(Map.class);
            verify(repository, org.mockito.Mockito.times(2)).upsertAll(captor.capture());

            assertEquals(captor.getAllValues().get(0).keySet(),
                    captor.getAllValues().get(1).keySet(),
                    "ids are derived from the bank's own transaction id, so a re-upload "
                            + "overwrites rather than duplicates");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void headerRowsAndBlankAmountRowsAreIgnored() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = buildStatement(workbook);
            // A dated row with neither Paid Out nor Paid In populated.
            Row empty = sheet.createRow(4);
            writeDate(workbook, empty, LocalDate.of(2025, 5, 3));
            empty.createCell(BankStatementMirrorService.COL_TRANSACTION_ID)
                    .setCellValue("TX-EMPTY");

            when(repository.upsertAll(anyMap())).thenReturn(2);
            service.mirror(sheet, "bog");

            ArgumentCaptor<Map<String, Map<String, Object>>> captor =
                    ArgumentCaptor.forClass(Map.class);
            verify(repository).upsertAll(captor.capture());

            assertEquals(2, captor.getValue().size());
            assertFalse(captor.getValue().containsKey("bog|TX-EMPTY"),
                    "a row with no money on either side carries no information");
            assertTrue(captor.getValue().containsKey("bog|TX-OUT-1"));
        }
    }

    // ==================== fixture ====================

    /** Reproduces the verified export: two header rows, then one outflow and one inflow. */
    private Sheet buildStatement(Workbook workbook) {
        Sheet sheet = workbook.createSheet("Statement");

        Row georgian = sheet.createRow(0);
        georgian.createCell(BankStatementMirrorService.COL_DATE).setCellValue("თარიღი");
        georgian.createCell(BankStatementMirrorService.COL_PAID_OUT).setCellValue("გასული თანხა");
        georgian.createCell(BankStatementMirrorService.COL_PAID_IN).setCellValue("შემოსული თანხა");

        Row english = sheet.createRow(1);
        english.createCell(BankStatementMirrorService.COL_DATE).setCellValue("Date");
        english.createCell(BankStatementMirrorService.COL_PAID_OUT).setCellValue("Paid Out");
        english.createCell(BankStatementMirrorService.COL_PAID_IN).setCellValue("Paid In");

        Row outflow = sheet.createRow(2);
        writeDate(workbook, outflow, LocalDate.of(2025, 5, 1));
        outflow.createCell(BankStatementMirrorService.COL_DESCRIPTION).setCellValue("ნაღდი ფულის გატანა");
        outflow.createCell(BankStatementMirrorService.COL_PAID_OUT).setCellValue(12850);
        outflow.createCell(BankStatementMirrorService.COL_PARTNER_NAME).setCellValue("შპს მაგსი");
        outflow.createCell(BankStatementMirrorService.COL_PARTNER_TAX_CODE).setCellValue("405135946");
        outflow.createCell(BankStatementMirrorService.COL_TRANSACTION_ID).setCellValue("TX-OUT-1");

        Row inflow = sheet.createRow(3);
        writeDate(workbook, inflow, LocalDate.of(2025, 5, 2));
        inflow.createCell(BankStatementMirrorService.COL_DESCRIPTION).setCellValue("საქონლის ღირებულება");
        inflow.createCell(BankStatementMirrorService.COL_PAID_IN).setCellValue(1150);
        inflow.createCell(BankStatementMirrorService.COL_PARTNER_NAME).setCellValue("შპს მაგსი");
        inflow.createCell(BankStatementMirrorService.COL_PARTNER_TAX_CODE).setCellValue("405135946");
        inflow.createCell(BankStatementMirrorService.COL_TRANSACTION_ID).setCellValue("TX-IN-1");

        return sheet;
    }

    /** Writes a date-formatted numeric cell, as the real export does. */
    private void writeDate(Workbook workbook, Row row, LocalDate date) {
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
        var cell = row.createCell(BankStatementMirrorService.COL_DATE);
        cell.setCellValue(date);
        cell.setCellStyle(style);
    }

    private static Map<String, Object> findByReference(Map<String, Map<String, Object>> rows,
                                                       String reference) {
        return rows.values().stream()
                .filter(r -> reference.equals(r.get("reference")))
                .findFirst().orElse(null);
    }
}
