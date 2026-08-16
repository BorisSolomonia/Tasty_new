package ge.tastyerp.common.dto.auditlayer;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One transaction behind a statement figure (BOR-92 v2) — a document line, a
 * bank row, or a payment — in one shape so every drill-down renders the same
 * table. Fields that do not apply to the kind are null.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditStatementTransactionDto {

    /** Stable id within its source: document row id, bank row id, or payment id. */
    private String id;
    /** DOCUMENT_LINE | BANK_ROW | PAYMENT | CASH_PAYMENT */
    private String kind;
    private LocalDate date;
    /** PURCHASE / SALE for documents, DEBIT / CREDIT for bank rows, null for payments. */
    private String direction;
    private BigDecimal amount;

    private String counterpartyTin;
    private String counterpartyName;

    // document lines
    private String productName;
    /** Resolved product group (with overrides applied). */
    private String category;
    private BigDecimal quantityKg;
    private String unit;
    private String waybillId;

    // bank rows and payments
    private String description;
    private String reference;
    private String source;

    // audit-layer mapping, where the row is mappable
    private AuditSourceType sourceType;
    private String sourceRowId;
    private AuditMappingStatus mappingStatus;
    /** Human-readable "category · status → counterparty" of the slices, if any. */
    private String mappingSummary;
    private BigDecimal unresolvedAmount;
}
