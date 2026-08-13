package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.audit.ProductMovementDto;
import ge.tastyerp.common.dto.waybill.WaybillType;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who actually sold goods to this business, according to RS.ge.
 *
 * <p>Built from purchase documents — the {@code get_buyer_waybills} feed, where
 * this company is the buyer — so membership is a <b>documented fact</b> rather
 * than an inference from a counterparty's name. That distinction is what lets
 * the mapping engine classify a bank transfer as a supplier payment
 * automatically: the counterparty's tax code appears on a purchase document, so
 * calling the payment a settlement is a statement about evidence, not a guess.</p>
 *
 * <p>The inverse is the more interesting audit signal. Money leaving the bank to
 * a counterparty that <b>never appears as a seller</b> is not a supplier
 * settlement, however plausible the name looks, and must never be counted toward
 * the purchase-coverage control.</p>
 */
public final class AuditSupplierRegistry {

    private final Map<String, BigDecimal> purchaseValueByTin;
    private final Map<String, String> nameByTin;

    private AuditSupplierRegistry(Map<String, BigDecimal> purchaseValueByTin,
                                  Map<String, String> nameByTin) {
        this.purchaseValueByTin = purchaseValueByTin;
        this.nameByTin = nameByTin;
    }

    /**
     * @param movements RS.ge document lines; only {@link WaybillType#PURCHASE}
     *                  rows contribute, and their {@code counterpartyId} is the
     *                  seller's tax code
     */
    public static AuditSupplierRegistry from(List<ProductMovementDto> movements) {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        if (movements != null) {
            for (ProductMovementDto m : movements) {
                if (m.getType() != WaybillType.PURCHASE) {
                    continue;
                }
                String tin = m.getCounterpartyId() == null ? null : m.getCounterpartyId().trim();
                if (tin == null || tin.isEmpty()) {
                    continue;
                }
                values.merge(tin, m.getAmount() == null ? BigDecimal.ZERO : m.getAmount(),
                        BigDecimal::add);
                names.putIfAbsent(tin, m.getProductName());
            }
        }
        return new AuditSupplierRegistry(values, names);
    }

    public static AuditSupplierRegistry empty() {
        return new AuditSupplierRegistry(Map.of(), Map.of());
    }

    /** True when this tax code appears as a seller on at least one purchase document. */
    public boolean isDocumentedSupplier(String tin) {
        return tin != null && purchaseValueByTin.containsKey(tin.trim());
    }

    /** Total documented purchase value from this counterparty; zero when none. */
    public BigDecimal documentedPurchases(String tin) {
        if (tin == null) {
            return BigDecimal.ZERO;
        }
        return purchaseValueByTin.getOrDefault(tin.trim(), BigDecimal.ZERO);
    }

    public Map<String, BigDecimal> purchaseValueByTin() {
        return Collections.unmodifiableMap(purchaseValueByTin);
    }

    public String nameOf(String tin) {
        return tin == null ? null : nameByTin.get(tin.trim());
    }

    public int size() {
        return purchaseValueByTin.size();
    }
}
