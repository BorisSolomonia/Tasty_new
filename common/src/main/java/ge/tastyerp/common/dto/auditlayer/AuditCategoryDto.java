package ge.tastyerp.common.dto.auditlayer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A mapping category (BOR-89 §6 "Categories").
 *
 * <p>The built-in set is seeded but not closed — users create their own. The
 * behavioural flags below are what the aggregates read; a custom category
 * participates in the maths exactly as a built-in one does, according to the
 * flags its creator chose.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditCategoryDto {

    /** Stable code, used as the Firestore document id. */
    private String code;

    /** Display label. */
    private String label;

    /** False for user-created categories; built-ins cannot be deleted. */
    private boolean builtIn;

    /** Counts toward supplier settlement in the §4 coverage control. */
    private boolean supplierSettlement;

    /** Counts as money genuinely received from a customer. */
    private boolean customerReceipt;

    /** Counts as spending that is not supplier settlement. */
    private boolean nonSupplierExpense;

    /** Cash that left the bank and came back — nets out of real spend. */
    private boolean cashReturn;

    /** Paper-only: creates or removes accounting cash with no real movement. */
    private boolean paperOnly;

    /** Explicitly unresolved — the amount stays visible as a gap. */
    private boolean unresolved;

    /** Longer explanation shown in the mapping UI. */
    private String description;
}
