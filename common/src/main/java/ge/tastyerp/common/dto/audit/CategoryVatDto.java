package ge.tastyerp.common.dto.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Per-category VAT position (BOR-76 Part 4), derived from documented RS.ge
 * movement line amounts (18% inclusive: {@code vat = amount · 18/118}).
 *
 * <p>The headline {@code vatPayable = salesVat − purchaseVat} is the ACTUAL
 * liability from real documents (per the confirmed decision). {@code writeOffPercent},
 * {@code documentedPurchaseKg} and {@code documentedSoldKg} are context showing
 * the physical justification for the purchased-vs-sold kg gap.
 *
 * <p>{@code projectedVatPayable} is an OPTIONAL what-if (sold kg =
 * purchased·(1−writeOff)) for planning only — it is not the filed figure.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CategoryVatDto {
    private String category;

    private BigDecimal salesGross;
    private BigDecimal salesVat;       // output VAT
    private BigDecimal purchaseGross;
    private BigDecimal purchaseVat;    // input VAT
    private BigDecimal vatPayable;     // salesVat − purchaseVat (actual)

    private BigDecimal writeOffPercent;      // context
    private BigDecimal documentedPurchaseKg; // context
    private BigDecimal documentedSoldKg;     // context

    /** Optional what-if VAT if only purchased·(1−writeOff) kg were documented as sold. */
    private BigDecimal projectedVatPayable;
}
