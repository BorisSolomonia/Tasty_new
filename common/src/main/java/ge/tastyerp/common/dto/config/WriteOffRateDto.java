package ge.tastyerp.common.dto.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A user-editable "possible write-off" rate for one write-off category.
 *
 * The rate is expressed as a <b>percentage of the day's purchased kg</b> (e.g.
 * {@code 28} = 28%). Only the tracked write-off categories (BEEF, PORK) carry a
 * rate; FAT / OTHER are passthrough. Stored in Firestore config/write_off_rates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WriteOffRateDto {
    private String category;      // BEEF / PORK
    private BigDecimal percent;   // percentage of purchased kg, 0..100 (e.g. 28)
}
