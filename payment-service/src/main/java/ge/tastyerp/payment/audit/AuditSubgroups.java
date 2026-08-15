package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.AuditSubgroupDto;

import java.util.List;

/**
 * Built-in level-2 subgroups (BOR-92). Seeded, not closed: users add their own
 * through {@code POST /layer/subgroups}, and a custom subgroup participates in
 * the overview exactly like a built-in one.
 */
public final class AuditSubgroups {

    public static final String PURCHASE_ACT_NEEDED = "PURCHASE_ACT_NEEDED";
    public static final String CHECK_NEEDED = "CHECK_NEEDED";
    public static final String CHECK_RECEIVED = "CHECK_RECEIVED";
    public static final String UNMAPPED = "UNMAPPED";
    /** Synthetic node code for slices that carry no subgroup at all. */
    public static final String NONE = "NONE";

    private static final List<AuditSubgroupDto> BUILT_INS = List.of(
            sub(PURCHASE_ACT_NEEDED, "Purchase act needed",
                    "Money went out, the purchase act (მიღება-ჩაბარების აქტი) from the counterparty is still missing"),
            sub(CHECK_NEEDED, "Check needed",
                    "A check must be produced by / obtained from the counterparty for this amount"),
            sub(CHECK_RECEIVED, "Got check",
                    "The check for this amount is on hand"),
            sub(UNMAPPED, "Unmapped",
                    "Classified into a group but its paperwork status has not been decided"));

    private AuditSubgroups() {
    }

    public static List<AuditSubgroupDto> builtIns() {
        return BUILT_INS;
    }

    public static boolean isBuiltIn(String code) {
        return code != null && BUILT_INS.stream().anyMatch(s -> s.getCode().equals(code));
    }

    private static AuditSubgroupDto sub(String code, String label, String description) {
        return AuditSubgroupDto.builder().code(code).label(label).description(description).builtIn(true).build();
    }
}
