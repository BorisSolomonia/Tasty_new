package ge.tastyerp.common.dto.payment;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for Excel upload operations.
 * Contains validation results and processing summary.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExcelUploadResponse {

    private boolean success;
    private String message;

    // Async aggregation job ID (for status polling)
    private String aggregationJobId;

    // Totals
    private BigDecimal excelTotalAll;       // Sum of ALL Column E values
    private BigDecimal excelTotalWindow;    // Sum of payment window only
    private BigDecimal analyzedTotal;       // Sum of payments that will be/were analyzed
    private BigDecimal appTotal;            // Sum already in Firebase

    // Counts
    private int totalRowsProcessed;
    private int addedCount;
    private int duplicateCount;
    private int skippedCount;
    private int beforeWindowCount;

    // Validation
    private boolean validationPassed;
    private BigDecimal validationDifference;

    // Details (for debugging/audit)
    private List<TransactionDetail> addedTransactions;
    private List<TransactionDetail> duplicateTransactions;
    private List<SkippedTransaction> skippedTransactions;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TransactionDetail {
        private int rowIndex;
        private String customerId;
        private BigDecimal amount;
        private BigDecimal balance;
        private String date;
        private String uniqueCode;
        private String status;      // Added, Duplicate, BeforeWindow
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkippedTransaction {
        private int rowIndex;
        private String customerId;
        private BigDecimal amount;
        private String date;
        private String reason;
    }
}
