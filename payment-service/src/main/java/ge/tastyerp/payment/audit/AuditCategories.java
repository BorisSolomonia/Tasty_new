package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.AuditCategoryDto;

import java.util.List;

/**
 * The built-in mapping categories from BOR-89 §6.
 *
 * <p>These are <b>seeded, not closed</b>. Users create their own categories and a
 * custom category participates in every aggregate exactly as a built-in one does,
 * driven by the behavioural flags its creator chose. Nothing downstream switches
 * on a hardcoded category code — the aggregates read the flags. That is what lets
 * a user add, say, "owner drawings" without a code change.</p>
 */
public final class AuditCategories {

    private AuditCategories() {
    }

    public static final String CUSTOMER_RECEIPT = "CUSTOMER_RECEIPT";
    public static final String SUPPLIER_BANK_PAYMENT = "SUPPLIER_BANK_PAYMENT";
    public static final String SUPPLIER_CASH_PAYMENT = "SUPPLIER_CASH_PAYMENT";
    public static final String CASH_WITHDRAWAL_UNRESOLVED = "CASH_WITHDRAWAL_UNRESOLVED";
    public static final String CASH_REDEPOSIT = "CASH_REDEPOSIT";
    public static final String OTHER_INCOME = "OTHER_INCOME";
    public static final String NON_SUPPLIER_EXPENSE = "NON_SUPPLIER_EXPENSE";
    public static final String CUSTOMER_REFUND = "CUSTOMER_REFUND";
    public static final String SUPPLIER_REFUND = "SUPPLIER_REFUND";
    public static final String BANK_REVERSAL = "BANK_REVERSAL";
    public static final String OWNER_MOVEMENT = "OWNER_MOVEMENT";
    public static final String SUPPLIER_CHECK_EVIDENCE = "SUPPLIER_CHECK_EVIDENCE";
    public static final String PAPER_ONLY_CUSTOMER_RECEIPT = "PAPER_ONLY_CUSTOMER_RECEIPT";
    public static final String PAPER_ONLY_SALE = "PAPER_ONLY_SALE";
    public static final String PAPER_ONLY_SUPPLIER_PAYMENT = "PAPER_ONLY_SUPPLIER_PAYMENT";

    /**
     * @return the built-in catalogue, in the order the UI should list it.
     */
    public static List<AuditCategoryDto> builtIns() {
        return List.of(
                cat(CUSTOMER_RECEIPT, "Customer receipt",
                        "Real money received from a customer.",
                        b -> b.customerReceipt(true)),

                cat(SUPPLIER_BANK_PAYMENT, "Supplier bank payment",
                        "Money transferred from the bank directly to a supplier.",
                        b -> b.supplierSettlement(true)),

                cat(SUPPLIER_CASH_PAYMENT, "Supplier real cash payment",
                        "Part of a cash withdrawal the operator confirms reached a supplier.",
                        b -> b.supplierSettlement(true)),

                cat(CASH_WITHDRAWAL_UNRESOLVED, "Cash withdrawal — unresolved",
                        "Withdrawn cash whose destination is not yet established. "
                                + "Stays visible as a gap; never quietly assigned to a supplier.",
                        b -> b.unresolved(true)),

                cat(CASH_REDEPOSIT, "Cash returned / redeposited",
                        "Cash that left the bank and came back. Nets out of real spending.",
                        b -> b.cashReturn(true)),

                cat(OTHER_INCOME, "Other income",
                        "Money in that is not a customer receipt.",
                        b -> b),

                cat(NON_SUPPLIER_EXPENSE, "Non-supplier expense",
                        "Real spending that buys no inventory — transport, wages, fuel.",
                        b -> b.nonSupplierExpense(true)),

                cat(CUSTOMER_REFUND, "Customer refund",
                        "Money returned to a customer.",
                        b -> b.nonSupplierExpense(true)),

                cat(SUPPLIER_REFUND, "Supplier refund",
                        "Money returned by a supplier.",
                        b -> b),

                cat(BANK_REVERSAL, "Bank reversal / correction",
                        "A bank-side correction that offsets another row.",
                        b -> b),

                cat(OWNER_MOVEMENT, "Loan / owner movement",
                        "Funds moving between the business and its owner.",
                        b -> b),

                cat(SUPPLIER_CHECK_EVIDENCE, "Supplier check / payment evidence",
                        "A receipt held for a supplier. Evidence only — never proof that "
                                + "money moved.",
                        b -> b),

                cat(PAPER_ONLY_CUSTOMER_RECEIPT, "Paper-only customer receipt",
                        "A receipt document with no real money behind it. Creates paper cash.",
                        b -> b.paperOnly(true)),

                cat(PAPER_ONLY_SALE, "Paper-only sale",
                        "A sale document with no real sale behind it. Reduces document stock.",
                        b -> b.paperOnly(true)),

                cat(PAPER_ONLY_SUPPLIER_PAYMENT, "Paper-only supplier payment",
                        "A supplier payment document with no real money behind it. "
                                + "Reduces paper cash.",
                        b -> b.paperOnly(true))
        );
    }

    private interface Tweak {
        AuditCategoryDto.AuditCategoryDtoBuilder apply(AuditCategoryDto.AuditCategoryDtoBuilder b);
    }

    private static AuditCategoryDto cat(String code, String label, String description, Tweak tweak) {
        return tweak.apply(AuditCategoryDto.builder()
                        .code(code)
                        .label(label)
                        .description(description)
                        .builtIn(true))
                .build();
    }
}
