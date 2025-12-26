package ge.tastyerp.common.dto.waybill;

/**
 * Waybill direction/type from Tasty ERP perspective.
 *
 * SALE: we sold goods (customer is BUYER)
 * PURCHASE: we purchased goods (customer is SELLER)
 */
public enum WaybillType {
    SALE,
    PURCHASE
}

