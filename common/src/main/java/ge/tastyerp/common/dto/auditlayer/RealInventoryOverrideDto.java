package ge.tastyerp.common.dto.auditlayer;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Manually confirmed real on-hand inventory for a product (BOR-89 §4A).
 *
 * <p>The reality anchor of the whole module. In this business real stock is
 * almost always 0 kg, but it must be editable, and every edit is logged.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RealInventoryOverrideDto {

    /** Firestore document id: {@code product|asOfDate}. */
    private String id;

    /** Standardised product name. */
    private String productName;

    /** The date this confirmation describes. */
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate asOfDate;

    /** Confirmed real stock. Defaults to zero, which is the normal case. */
    private BigDecimal realKg;

    /** Why the confirmation says what it says. */
    private String note;

    /** Self-declared operator name (decision D-4), not an authenticated user. */
    private String updatedBy;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss")
    private LocalDateTime updatedAt;
}
