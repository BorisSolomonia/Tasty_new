package ge.tastyerp.common.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * What RS.ge says the documents of a period add up to, next to what their
 * goods lines add up to (BOR-92 v6). The audit pages count <em>lines</em>
 * (they need product groups); this is the independent figure they are checked
 * against, so a waybill whose goods never arrived, or whose lines do not add
 * up to its FULL_AMOUNT, is a stated gap instead of a silent understatement.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentTotalsDto {

    private String startDate;
    private String endDate;
    private Side purchase;
    private Side sale;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Side {
        /** Waybills in the period (return waybills included, counted negative in the amounts). */
        private int waybills;
        /** Σ of each waybill's own total (RS.ge FULL_AMOUNT), returns negated. */
        private BigDecimal documentAmount;
        /** Σ of goods lines' total price, returns negated — what the audit pages count. */
        private BigDecimal linesAmount;
        /** Waybills for which no goods line exists (fetched empty, or fetch failed). */
        private int waybillsWithoutGoods;
        private BigDecimal amountWithoutGoods;
        /** Waybills with goods whose lines differ from the waybill total by more than a tetri. */
        private int waybillsWithMismatch;
        /** Σ (document total − lines) over mismatched waybills. */
        private BigDecimal mismatchAmount;
        /** Distinct sellers (purchase) / buyers (sale) by tax code. */
        private int counterparties;
    }
}
