package ge.tastyerp.common.dto.auditlayer;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The single canonical three-flow payload (BOR-89 §4, §8, §10).
 *
 * <p>All eight UX variants render <b>this one object</b>. That is what makes the
 * ticket's rule "they must not have different data scope" structurally true
 * rather than a promise: there is only one payload to disagree about.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditFlowsDto {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    private Inventory inventory;
    private Cash cash;
    private Documentation documentation;

    /** Every rule that fired for this period, across all three flows. */
    private List<AuditAlertDto> alerts;

    /**
     * Non-fatal notes about data availability — e.g. that no bank outflows have
     * been imported yet. Surfaced in the UI so an empty number is never mistaken
     * for a clean number.
     */
    private List<String> dataWarnings;

    // ------------------------------------------------------------------
    // Inventory flow — documented movement versus manually confirmed reality
    // ------------------------------------------------------------------

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Inventory {

        private List<ProductRow> products;

        private BigDecimal documentPurchaseKg;
        private BigDecimal documentSaleKg;
        private BigDecimal documentWriteOffKg;

        /** Σ of per-product document stock on hand. */
        private BigDecimal documentStockKg;

        /** Σ of per-product manually confirmed real stock. Normally zero. */
        private BigDecimal realStockKg;

        /** {@code documentStockKg − realStockKg}. */
        private BigDecimal gapKg;

        private int positiveGapProducts;
        private int negativeGapProducts;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class ProductRow {
            private String productName;
            private String category;
            private BigDecimal purchaseKg;
            private BigDecimal saleKg;
            private BigDecimal writeOffKg;
            /** Write-off rate actually applied, as a whole percentage. */
            private BigDecimal writeOffPercent;
            private BigDecimal documentStockKg;
            private BigDecimal realStockKg;
            private BigDecimal gapKg;
            /** True when real stock was explicitly confirmed rather than assumed 0. */
            private boolean realStockConfirmed;
            /** Cash gap attributable to this product, for the inventory-first view. */
            private BigDecimal relatedCashGap;
            /** Count of flagged documents touching this product. */
            private int flaggedDocumentCount;
        }
    }

    // ------------------------------------------------------------------
    // Cash flow — first-class, equal in weight to inventory
    // ------------------------------------------------------------------

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Cash {

        // --- what the bank actually did ---
        private BigDecimal bankInflow;
        private BigDecimal bankOutflow;
        private BigDecimal cashWithdrawals;
        private BigDecimal mappedWithdrawalAmount;
        private BigDecimal unresolvedWithdrawalAmount;

        // --- where money went ---
        private BigDecimal directBankSupplierPayments;
        private BigDecimal supplierAllocatedCashSettlements;
        private BigDecimal customerBankReceipts;
        private BigDecimal realCustomerCashReceipts;
        private BigDecimal otherIncome;
        private BigDecimal nonSupplierExpenses;
        private BigDecimal refundsAndReversals;
        private BigDecimal cashReturnedOrRedeposited;

        // --- evidence, which is not money ---
        private BigDecimal checksOnHand;
        private BigDecimal checksSupportedByRealMoney;
        private BigDecimal unsupportedChecks;
        private int unclassifiedCheckCount;

        // --- paper cash bridge ---
        private BigDecimal onPaperSalesValue;
        private BigDecimal onPaperCustomerReceiptValue;
        private BigDecimal realMoneyReceivedAgainstPaperSales;
        private BigDecimal paperCashCreated;
        private BigDecimal onPaperSupplierPaymentValue;
        private BigDecimal realMoneySupportingSupplierPayments;
        private BigDecimal paperCashReduced;
        private BigDecimal netUnexplainedPaperCash;

        // --- supplier purchase coverage control (§4B, critical) ---
        private BigDecimal realOutstandingSupplierDebt;
        /** direct bank supplier payments + supplier-allocated settlements + debt. */
        private BigDecimal supplierSettlementAndDebt;
        private BigDecimal documentedSupplierPurchases;
        /** Positive only when the control is breached. */
        private BigDecimal excessOverDocumentedPurchases;
        /** documented purchases − settlement & debt. Positive is not per se wrong. */
        private BigDecimal uncoveredPurchaseBalance;
        private boolean coverageBreach;
        /** Counterparties responsible for a breach. */
        private List<String> coverageBreachSubjects;

        // --- what nobody has classified yet ---
        private int unmappedInflowCount;
        private BigDecimal unmappedInflowAmount;
        private int unmappedOutflowCount;
        private BigDecimal unmappedOutflowAmount;

        /** Total bank rows available in the period, mapped or not. */
        private int bankRowCount;

        /**
         * Per-counterparty reconciliation of what RS.ge says was bought against
         * what the bank says was paid (BOR-89 §4B).
         */
        private List<SupplierCoverageRow> supplierCoverage;

        /** Paid out to counterparties that never appear as a seller on RS.ge. */
        private BigDecimal paidToUndocumentedCounterparties;
        private int undocumentedCounterpartyCount;

        /** Documented suppliers that received no bank payment at all. */
        private int unpaidDocumentedSupplierCount;
        private BigDecimal unpaidDocumentedSupplierPurchases;

        /** Outflow that left with no counterparty tax code at all. */
        private BigDecimal outflowWithoutCounterpartyId;
        private int outflowWithoutCounterpartyIdCount;

        @Data
        @Builder
        @NoArgsConstructor
        @AllArgsConstructor
        public static class SupplierCoverageRow {
            private String counterpartyTin;
            private String counterpartyName;
            /** Σ RS.ge purchase-document value from this counterparty. */
            private BigDecimal documentedPurchases;
            /** Σ bank money paid to this counterparty. */
            private BigDecimal bankPaid;
            /** Manually asserted outstanding debt to them. */
            private BigDecimal realDebt;
            /** documentedPurchases − bankPaid − realDebt. Positive = unexplained. */
            private BigDecimal uncovered;
            /** True when they were paid but never sold anything on paper. */
            private boolean paidWithoutDocumentation;
            /** True when they sold on paper but were never paid by bank. */
            private boolean documentedButUnpaid;
            private int bankRowCount;
        }
    }

    // ------------------------------------------------------------------
    // Documentation flow — RS.ge documents and their two consequences
    // ------------------------------------------------------------------

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Documentation {

        private BigDecimal documentPurchaseValue;
        private BigDecimal documentPurchaseKg;
        private BigDecimal documentSalesValue;
        private BigDecimal documentSalesKg;
        private BigDecimal writeOffKg;

        private BigDecimal paperOnlySalesValue;
        private int paperOnlySalesCount;
        private BigDecimal paperOnlyCustomerPaymentValue;
        private int paperOnlyCustomerPaymentCount;
        private BigDecimal unsupportedSupplierDocumentValue;
        private int unsupportedSupplierDocumentCount;

        private int unmappedDocumentRowCount;
        private BigDecimal unmappedDocumentValue;

        private int flaggedDocumentCount;
        private BigDecimal flaggedDocumentValue;

        /** Total RS.ge document rows in the period. */
        private int documentRowCount;
    }
}
