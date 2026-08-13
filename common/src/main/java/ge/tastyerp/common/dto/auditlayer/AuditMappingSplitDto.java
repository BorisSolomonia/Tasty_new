package ge.tastyerp.common.dto.auditlayer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * One allocation of a source row's amount to a category (BOR-89 §6).
 *
 * <p>A bank withdrawal of 12,850 GEL may be split into a supplier settlement, a
 * transport expense, a redeposit and an unresolved remainder. The remainder is
 * never stored — it is derived as {@code source amount − Σ splits} so it can
 * never silently disagree with the splits.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditMappingSplitDto {

    /** Category code this slice is allocated to. */
    private String categoryCode;

    /** Counterparty this slice settles with, when the category implies one. */
    private String counterpartyName;

    /** Counterparty TIN, when known. Identity for supplier/customer rollups. */
    private String counterpartyTin;

    /** Standardised product, for slices that carry an inventory meaning. */
    private String productName;

    /** Amount allocated to this slice. Always positive. */
    private BigDecimal amount;

    /** Quantity allocated, for inventory-bearing slices. */
    private BigDecimal quantityKg;

    /** Free-text justification for this particular slice. */
    private String note;
}
