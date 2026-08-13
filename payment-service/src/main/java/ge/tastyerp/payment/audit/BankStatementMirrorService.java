package ge.tastyerp.payment.audit;

import ge.tastyerp.common.exception.ValidationException;
import ge.tastyerp.common.util.DateUtils;
import ge.tastyerp.payment.bank.tbc.BankTransaction;
import ge.tastyerp.payment.bank.tbc.BankTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mirrors <b>every</b> uploaded statement row — money in <em>and</em> money out —
 * into the canonical {@code bankTransactions} collection.
 *
 * <h3>Why this exists</h3>
 * <p>{@code ExcelProcessingService} reads the amount from column E only
 * ("შემოსული თანხა / Paid In"). Column D ("გასული თანხა / Paid Out") was never
 * read, so no cash withdrawal, supplier transfer, fee or reversal had ever
 * entered the system. Without outflows the entire BOR-89 cash flow has no data
 * to reconcile.</p>
 *
 * <p>This runs as a <b>separate pass</b> rather than a change to the payment
 * logic. Customer-receipt processing, deduplication, the overlap window and the
 * {@code payments} collection are untouched; the audit layer simply gains a
 * complete copy of the statement to reason about.</p>
 *
 * <h3>Verified column layout</h3>
 * <p>Confirmed identical across two real exports: {@code example/example.xlsx}
 * (Bank of Georgia) and {@code account_statement_18083950_01012023_13082026.xlsx}
 * (TBC, account GE47TB7625036080100008, 9,333 rows over 2023-01-03 → 2026-08-13).
 * Two header rows — Georgian then English — with data from the third. Direction
 * is carried by <b>which column is populated</b>, never by a sign. Every row in
 * the TBC export carries a Transaction ID in column W, so the derived document
 * ids make re-import idempotent.</p>
 *
 * <p>TBC exports place the ledger on the <b>second</b> sheet behind a Summary
 * tab; BOG on the first. Sheet selection matches the payments importer.</p>
 * <pre>
 *   A(0)  თარიღი / Date                        M(12) partner bank code
 *   B(1)  დანიშნულება / Description            N(13) partner bank
 *   C(2)  დამატებითი ინფორმაცია                U(20) ოპ. კოდი / Op. Code
 *   D(3)  გასული თანხა / Paid Out              V(21) დამატებითი დანიშნულება
 *   E(4)  შემოსული თანხა / Paid In             W(22) ტრანზაქციის ID
 *   F(5)  ნაშთი / Balance
 *   G(6)  ტრანზაქციის ტიპი / Type
 *   I(8)  საბუთის № / Document Number
 *   J(9)  პარტნიორის ანგარიში / Partner's Account
 *   K(10) პარტნიორი / Partner's Name
 *   L(11) პარტნიორის საგადასახადო კოდი / Partner's Tax Code
 * </pre>
 *
 * <h3>What this does not do</h3>
 * <p>Mirroring never classifies. A mirrored row lands unmapped; deciding what it
 * means is the mapping engine's job, and — for anything resting on a description
 * keyword rather than a fact the bank itself asserts — a person's.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BankStatementMirrorService {

    // Verified against the real export; see the class javadoc.
    static final int COL_DATE = 0;
    static final int COL_DESCRIPTION = 1;
    static final int COL_ADDITIONAL_INFO = 2;
    static final int COL_PAID_OUT = 3;
    static final int COL_PAID_IN = 4;
    static final int COL_TYPE = 6;
    static final int COL_DOCUMENT_NUMBER = 8;
    static final int COL_PARTNER_ACCOUNT = 9;
    static final int COL_PARTNER_NAME = 10;
    static final int COL_PARTNER_TAX_CODE = 11;
    static final int COL_OP_CODE = 20;
    static final int COL_ADDITIONAL_DESCRIPTION = 21;
    static final int COL_TRANSACTION_ID = 22;

    /** Banks whose statements this service knows how to open. */
    private static final List<String> SUPPORTED_BANKS = List.of("tbc", "bog");

    private final BankTransactionRepository bankTransactionRepository;

    /**
     * Mirrors an uploaded statement workbook into {@code bankTransactions} and
     * nothing else.
     *
     * <p>Deliberately separate from {@code ExcelProcessingService}: that path also
     * creates customer-receipt rows in the live {@code payments} ledger, which a
     * historical audit backfill has no business touching. This one is read-only
     * with respect to every collection except the audit layer's own bank-row
     * store, so a three-and-a-half-year statement can be loaded without
     * disturbing operational data.</p>
     *
     * <p>Sheet selection mirrors the payments importer exactly — TBC exports put
     * the ledger on the second sheet behind a Summary tab, BOG on the first —
     * so the two paths can never disagree about which sheet is the statement.</p>
     */
    public MirrorResult mirrorUpload(MultipartFile file, String bank) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("file", "A statement file is required");
        }
        String normalized = bank == null ? "" : bank.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_BANKS.contains(normalized)) {
            throw new ValidationException("bank", "Bank must be one of " + SUPPORTED_BANKS);
        }
        String name = file.getOriginalFilename();
        if (name == null || !(name.toLowerCase(Locale.ROOT).endsWith(".xlsx")
                || name.toLowerCase(Locale.ROOT).endsWith(".xls"))) {
            throw new ValidationException("file", "File must be an Excel file (.xlsx or .xls)");
        }

        try (Workbook workbook = WorkbookFactory.create(file.getInputStream())) {
            int sheetIndex = "tbc".equals(normalized) ? 1 : 0;
            if (workbook.getNumberOfSheets() <= sheetIndex) {
                log.warn("Sheet {} not present in {}; falling back to sheet 0", sheetIndex, name);
                sheetIndex = 0;
            }
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            int mirrored = mirror(sheet, normalized);
            return new MirrorResult(normalized, sheet.getSheetName(), sheetIndex,
                    sheet.getLastRowNum() + 1, mirrored);
        } catch (IOException e) {
            throw new ValidationException("file", "Could not read the Excel file: " + e.getMessage());
        }
    }

    /** What an import actually did, so the caller is never guessing. */
    public record MirrorResult(String bank, String sheetName, int sheetIndex,
                               int sheetRows, int mirroredRows) {
    }

    /**
     * Reads a statement sheet and upserts every dated row into
     * {@code bankTransactions}.
     *
     * @param sheet the statement sheet (the caller already picked the right one
     *              for the bank)
     * @param bank  {@code bog} or {@code tbc}; stored as the row source
     * @return how many rows were mirrored
     */
    public int mirror(Sheet sheet, String bank) {
        Map<String, Map<String, Object>> rowsById = new LinkedHashMap<>();
        String source = bank == null ? "unknown" : bank.trim().toLowerCase(Locale.ROOT);
        LocalDateTime importedAt = LocalDateTime.now();

        int skippedUndated = 0;
        for (int rowIndex = 0; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            LocalDate date = parseDate(row);
            if (date == null) {
                // Both header rows and any blank separator land here.
                skippedUndated++;
                continue;
            }

            BigDecimal paidOut = decimal(row, COL_PAID_OUT);
            BigDecimal paidIn = decimal(row, COL_PAID_IN);
            boolean hasOut = isPositive(paidOut);
            boolean hasIn = isPositive(paidIn);
            if (!hasOut && !hasIn) {
                continue;
            }
            if (hasOut && hasIn) {
                // Not seen in the sample, but a row cannot be both directions at
                // once. Log rather than silently pick one.
                log.warn("Statement row {} populates both Paid Out and Paid In; "
                        + "mirroring the outflow and flagging the row", rowIndex + 1);
            }

            String direction = hasOut ? BankTransaction.DEBIT : BankTransaction.CREDIT;
            BigDecimal amount = hasOut ? paidOut : paidIn;

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("source", source);
            data.put("date", date.toString());
            data.put("direction", direction);
            data.put("amount", amount.setScale(2, RoundingMode.HALF_UP).doubleValue());
            data.put("currency", "GEL");
            data.put("accountNumber", null);
            data.put("counterparty", string(row, COL_PARTNER_NAME));
            data.put("counterpartyInn", string(row, COL_PARTNER_TAX_CODE));
            data.put("counterpartyAccount", string(row, COL_PARTNER_ACCOUNT));
            data.put("description", string(row, COL_DESCRIPTION));
            data.put("additionalInformation", string(row, COL_ADDITIONAL_INFO));
            data.put("additionalDescription", string(row, COL_ADDITIONAL_DESCRIPTION));
            data.put("transactionType", string(row, COL_TYPE));
            data.put("opCode", string(row, COL_OP_CODE));
            data.put("documentNumber", string(row, COL_DOCUMENT_NUMBER));
            data.put("reference", string(row, COL_TRANSACTION_ID));
            data.put("isExpense", hasOut);
            data.put("isIncome", !hasOut);
            data.put("bothDirections", hasOut && hasIn);
            data.put("importedAt", importedAt.toString());

            rowsById.put(documentId(source, date, direction, amount, row), data);
        }

        int written = bankTransactionRepository.upsertAll(rowsById);
        log.info("Mirrored {} statement rows into bankTransactions for source '{}' "
                        + "({} undated rows skipped, incl. the two header rows)",
                written, source, skippedUndated);
        return written;
    }

    /**
     * Deterministic id so re-uploading an overlapping file updates rows instead
     * of duplicating them. The bank's own transaction id (column W) is the
     * natural key; when it is absent the id falls back to the row's content.
     */
    static String documentId(String source, LocalDate date, String direction,
                             BigDecimal amount, Row row) {
        String transactionId = string(row, COL_TRANSACTION_ID);
        if (transactionId != null && !transactionId.isBlank()) {
            return source + "|" + transactionId.trim();
        }
        String content = String.join("|",
                date.toString(),
                direction,
                amount.setScale(2, RoundingMode.HALF_UP).toPlainString(),
                nullSafe(string(row, COL_PARTNER_TAX_CODE)),
                nullSafe(string(row, COL_PARTNER_ACCOUNT)),
                nullSafe(string(row, COL_DESCRIPTION)),
                nullSafe(string(row, COL_DOCUMENT_NUMBER)));
        return source + "|h" + Integer.toHexString(content.hashCode());
    }

    // ==================== cell reading ====================

    private static LocalDate parseDate(Row row) {
        Cell cell = row.getCell(COL_DATE);
        if (cell == null) {
            return null;
        }
        try {
            if (cell.getCellType() == org.apache.poi.ss.usermodel.CellType.NUMERIC) {
                // Dates arrive as Excel serials in this export.
                if (DateUtil.isCellDateFormatted(cell)) {
                    return cell.getLocalDateTimeCellValue().toLocalDate();
                }
                return DateUtil.getLocalDateTime(cell.getNumericCellValue()).toLocalDate();
            }
            return DateUtils.parseDate(cell.getStringCellValue());
        } catch (Exception e) {
            return null;
        }
    }

    private static BigDecimal decimal(Row row, int column) {
        Cell cell = row.getCell(column);
        if (cell == null) {
            return null;
        }
        try {
            return switch (cell.getCellType()) {
                case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue());
                case STRING -> {
                    String raw = cell.getStringCellValue();
                    if (raw == null || raw.isBlank()) {
                        yield null;
                    }
                    yield new BigDecimal(raw.replace(",", "").replace(" ", "").trim());
                }
                default -> null;
            };
        } catch (Exception e) {
            return null;
        }
    }

    private static String string(Row row, int column) {
        Cell cell = row.getCell(column);
        if (cell == null) {
            return null;
        }
        try {
            String value = switch (cell.getCellType()) {
                case STRING -> cell.getStringCellValue();
                case NUMERIC -> BigDecimal.valueOf(cell.getNumericCellValue())
                        .stripTrailingZeros().toPlainString();
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default -> null;
            };
            return value == null || value.isBlank() ? null : value.trim();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
