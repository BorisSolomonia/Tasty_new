package ge.tastyerp.common.dto.auditlayer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The statement at the top of the audit page (BOR-92 v2): one row per flow,
 * each with a period <b>total</b> and the same figure restricted to the
 * <b>chosen</b> counterparties, in income-statement order —
 * purchases → bank payments to suppliers → cash outflow → inventory → sales →
 * bank inflow → cash inflow.
 *
 * <p>"Chosen" is a saved selection (per operator) of supplier TINs and customer
 * TINs. Supplier-side rows (purchases, bank payments to suppliers, cash
 * outflow) use the supplier set; customer-side rows (sales, bank inflow, cash
 * inflow) use the customer set. Where inputs are missing the field is null,
 * never zero, and {@code notes} says why.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditStatementDto {

    private LocalDate startDate;
    private LocalDate endDate;
    private String operator;
    private Selection selection;

    /** ₾ of every RS.ge PURCHASE line in the period (kg only where the unit is kg). Parties = sellers. Products = product groups. */
    private Row purchases;
    /** ₾ of bank DEBIT slices whose category is flagged supplierSettlement, by slice counterparty (else the row's resolved TIN). */
    private Row bankPaymentsToSuppliers;
    /** ₾ of every bank DEBIT row; secondary = the part no slice covers ("unmapped"); chosen = rows/slices attributed to chosen suppliers. */
    private Row cashOutflow;
    /** Net movement on paper per product group and its value at the period's average purchase price. */
    private InventoryRow inventory;
    /** ₾ of every RS.ge SALE line; secondary = real (total − unreal); parties = buyers, flagged unreal where /audit-control says so. */
    private Row sales;
    /** ₾ of bank-statement payments from customers in the period (the {@code payments} collection the /payments page lists). */
    private Row bankInflow;
    /** ₾ of manual cash payments from customers in the period (the {@code manualCashPayments} collection the /payments page lists). */
    private Row cashInflow;

    private List<String> notes;

    // ------------------------------------------------------------------

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Selection {
        private List<String> suppliers;
        private List<String> customers;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Row {
        /** purchases | bankPaymentsToSuppliers | cashOutflow | sales | bankInflow | cashInflow */
        private String key;
        private String title;
        /** One sentence: what the figure counts and where it comes from. */
        private String definition;
        /** Which selection set "chosen" uses: SUPPLIERS or CUSTOMERS. */
        private String chosenBy;
        private BigDecimal total;
        /** Null where kg is not meaningful for the row. */
        private BigDecimal totalKg;
        /** Null when the relevant selection set is empty (nothing chosen ≠ zero). */
        private BigDecimal chosen;
        private BigDecimal chosenKg;
        /** Optional extra figure with its own label (e.g. "unmapped", "real"). */
        private BigDecimal secondary;
        private String secondaryLabel;
        private int rowCount;
        /** Counterparties of this row, each carrying whether it is currently chosen. */
        private List<Party> parties;
        /** Product groups (purchases and sales only); null elsewhere. */
        private List<ProductGroup> products;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Party {
        private String tin;
        private String name;
        private BigDecimal amount;
        private BigDecimal quantityKg;
        /** Row-specific extra: unmapped ₾ for cash outflow, null elsewhere. */
        private BigDecimal secondary;
        private int rowCount;
        private boolean chosen;
        /** Sales only: the customer is marked unreal on /audit-control. */
        private boolean unreal;
        /** How the identity was established when the source gave no TIN (bank rows). */
        private String identityBasis;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductGroup {
        private String category;
        private BigDecimal amount;
        private BigDecimal quantityKg;
        private BigDecimal chosenAmount;
        private BigDecimal chosenKg;
        private int rowCount;
        /** Distinct product names inside the group. */
        private int productCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InventoryRow {
        private String key;
        private String title;
        private String definition;
        /** Σ net kg over groups (may be negative when sales exceed purchases in the period). */
        private BigDecimal totalKg;
        /** Σ net kg × the group's average purchase price per kg this period; groups with no priced purchases are excluded and listed in {@code unpricedCategories}. */
        private BigDecimal totalValue;
        private List<String> unpricedCategories;
        private List<Level> levels;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Level {
        private String category;
        private BigDecimal purchasedKg;
        private BigDecimal purchasedAmount;
        private BigDecimal writeOffPercent;
        private BigDecimal writeOffKg;
        private BigDecimal soldKg;
        private BigDecimal soldAmount;
        /** purchased − write-off − sold for the period. Opening stock is not recorded, so this is a net movement. */
        private BigDecimal netKg;
        /** Purchase ₾ ÷ purchase kg on kg-lines this period; null when there were none. */
        private BigDecimal avgPurchasePricePerKg;
        /** netKg × avgPurchasePricePerKg; null when unpriced. */
        private BigDecimal value;
        /** Which suppliers the remaining kg came from, latest purchases first (LIFO). Empty when netKg ≤ 0. */
        private List<SupplierKg> stockBySupplier;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SupplierKg {
        private String tin;
        private String name;
        private BigDecimal quantityKg;
        private LocalDate lastPurchaseDate;
    }
}
