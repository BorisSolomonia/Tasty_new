package ge.tastyerp.common.dto.waybill;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Backend-calculated VAT summary for a period (sold vs purchased).
 *
 * Matches legacy formula: VAT = gross * 0.18 / 1.18, using only amounts > 0.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaybillVatSummaryDto {

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate startDate;

    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate endDate;

    private String cutoffDate;

    private long soldWaybillCount;
    private long purchasedWaybillCount;

    private long soldPositiveAmountCount;
    private long purchasedPositiveAmountCount;

    private BigDecimal soldGross;
    private BigDecimal purchasedGross;

    private BigDecimal soldVat;
    private BigDecimal purchasedVat;
    private BigDecimal netVat;
}

