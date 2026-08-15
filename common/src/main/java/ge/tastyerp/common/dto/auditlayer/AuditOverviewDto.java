package ge.tastyerp.common.dto.auditlayer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The top strip of the audit page (BOR-92): four flows — purchases, bank
 * payments to suppliers, cash outflow, sales — each as a total for the period
 * and the same figure restricted to the <em>chosen</em> supplier, plus the
 * drill-down each tile opens.
 *
 * <p>Every figure states its basis in the field javadoc. Where inputs are
 * missing (no opening stock, no supplier chosen) the field is null, never
 * zero, and {@code notes} says so.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditOverviewDto {

    private LocalDate startDate;
    private LocalDate endDate;

    /** The chosen supplier (TIN) the "chosen" columns are restricted to; null = none chosen. */
    private String supplierTin;
    private String supplierName;

    /** Every counterparty seen as a seller on an RS.ge purchase document in the period, for the picker. */
    private List<Counterparty> suppliers;

    private Purchases purchases;
    private BankPayments bankPaymentsToSuppliers;
    private CashOutflow cashOutflow;
    private Sales sales;
    private Inventory inventory;

    /** All level-2 subgroups (built-in + custom), so the UI can label codes. */
    private List<AuditSubgroupDto> subgroups;

    /** Plain-language caveats about what these numbers cannot say. */
    private List<String> notes;

    // ------------------------------------------------------------------

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Counterparty {
        private String tin;
        private String name;
        /** ₾ documented purchases from this seller (purchases tab / picker). */
        private BigDecimal purchases;
        /** ₾ real bank money paid to this counterparty (supplier-settlement splits on bank debits). */
        private BigDecimal bankPayments;
        /** ₾ paper cash-out attributed to this counterparty (unreal-sale chains). */
        private BigDecimal paperOutflow;
        private BigDecimal quantityKg;
        private int rowCount;
    }

    /** ₾ and kg for one product category (BEEF, PORK, …). */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CategoryAmount {
        private String category;
        private BigDecimal amount;
        private BigDecimal quantityKg;
        /** Same two figures restricted to the chosen supplier; null when none chosen. */
        private BigDecimal chosenAmount;
        private BigDecimal chosenQuantityKg;
        private int rowCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Purchases {
        /** ₾ of every RS.ge PURCHASE document line in the period (documented). */
        private BigDecimal total;
        private BigDecimal totalKg;
        /** Same, restricted to the chosen supplier; null when none chosen. */
        private BigDecimal chosen;
        private BigDecimal chosenKg;
        private List<CategoryAmount> byCategory;
        private List<Counterparty> bySupplier;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BankPayments {
        /** ₾ of bank DEBIT slices whose category is flagged supplierSettlement (real money). */
        private BigDecimal total;
        /** Same restricted to the chosen supplier (by the slice's counterparty, else the row's resolved TIN). */
        private BigDecimal toChosen;
        private List<Counterparty> bySupplier;
    }

    /** One (group → subgroup → counterparty) tree node with its ₾. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Bucket {
        private String code;
        private String label;
        private BigDecimal amount;
        private int rowCount;
        /** Present on group nodes: the subgroups; on subgroup nodes: the counterparties. */
        private List<Bucket> children;
        /** Present on counterparty nodes: the TIN. */
        private String tin;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CashOutflow {
        /** ₾ of every bank DEBIT row in the period (real money out). */
        private BigDecimal total;
        /** ₾ of DEBIT amount not covered by any slice. */
        private BigDecimal unmapped;
        private BigDecimal mapped;
        /** ₾ that left to the chosen supplier: every DEBIT slice or unmapped remainder whose counterparty (the slice's, else the row's) is that supplier, any group. Null when none chosen. */
        private BigDecimal toChosen;
        /** Real-money tree: group (category) → subgroup (document status) → counterparty. */
        private List<Bucket> groups;
        /** Paper tree from unreal-sale chains: category → subgroup → supplier. Never added to {@code total}. */
        private List<Bucket> paperGroups;
        private BigDecimal paperTotal;
        private int debitRowCount;
        private int unmappedRowCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Sales {
        /** ₾ of every RS.ge SALE document line in the period (documented). */
        private BigDecimal total;
        private BigDecimal totalKg;
        /** Documented sales to customers marked unreal, or lines a person mapped as paper-only sale. */
        private BigDecimal unreal;
        /** total − unreal. */
        private BigDecimal real;
        /** ₾ of unreal lines already carrying a mapping (chained to a supplier / paper receipt). */
        private BigDecimal unrealMapped;
        private BigDecimal unrealUnmapped;
        private List<CategoryAmount> byCategory;
        /** Unreal customers with their documented sales and how much of it is chained onwards. */
        private List<Counterparty> unrealCustomers;
        private int unrealRowCount;
    }

    /** kg attributed to one supplier under LIFO. */
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

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class InventoryCategory {
        private String category;
        private BigDecimal purchasedKg;
        private BigDecimal writeOffPercent;
        private BigDecimal writeOffKg;
        private BigDecimal soldKg;
        /** purchased − write-off − sold for the period. Opening stock is not recorded, so this is a net movement. */
        private BigDecimal netKg;
        /**
         * Which suppliers the remaining kg came from, latest purchases first (LIFO):
         * walk purchases backwards until netKg is covered. Empty when netKg ≤ 0.
         */
        private List<SupplierKg> stockBySupplier;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Inventory {
        private List<InventoryCategory> byCategory;
        private BigDecimal netKgTotal;
    }
}
