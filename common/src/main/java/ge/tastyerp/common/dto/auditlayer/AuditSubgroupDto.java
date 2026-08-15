package ge.tastyerp.common.dto.auditlayer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Level-2 mapping subgroup — the document status of a cash-outflow slice
 * (BOR-92 two-level mapping).
 *
 * <p>Level 1 is the {@link AuditCategoryDto} (supplier settlement, tax, expense,
 * money returned …); level 2 says what paperwork that slice needs or has:
 * "Purchase act needed", "Check needed", "Got check", "Unmapped". Seeded but
 * not closed — users add their own, exactly like categories.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditSubgroupDto {

    /** Stable code, used as the Firestore document id. */
    private String code;

    private String label;

    private String description;

    private boolean builtIn;
}
