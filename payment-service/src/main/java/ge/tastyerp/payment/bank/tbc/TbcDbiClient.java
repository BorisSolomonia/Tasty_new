package ge.tastyerp.payment.bank.tbc;

import ge.tastyerp.payment.bank.BankApiProperties;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * TBC DBI (Direct Bank Integration / mygemini) SOAP client.
 *
 * Ported from the Camora ERP benchmark implementation. Authentication is
 * mutual TLS (PKCS12 client certificate) plus a WS-Security UsernameToken
 * with a fresh nonce on every request — no OAuth, no session token.
 *
 * TBC requires a password change before first use and whenever it returns
 * fault CREDENTIALS_MUST_BE_CHANGED; {@link #changePassword} performs it with
 * the Digipass/OTP code carried in the WS-Security nonce. The new password is
 * held only in process memory — the operator must persist it to
 * TBC_DBI_PASSWORD in .env before the next restart.
 */
@Component
public class TbcDbiClient {

    static final int FIRST_PAGE_INDEX = 0;
    private static final int MAX_PAGES_PER_REQUEST = 1000;

    private final BankApiProperties properties;
    private volatile String runtimePassword;

    public TbcDbiClient(BankApiProperties properties) {
        this.properties = properties;
    }

    public List<BankTransaction> getAccountMovements(LocalDate dateFrom, LocalDate dateTo) {
        BankApiProperties.Tbc config = properties.getTbc();
        validateConfig(config, true);
        int pageSize = Math.max(1, Math.min(config.getPageSize(), 700));
        int pageIndex = FIRST_PAGE_INDEX;
        List<BankTransaction> all = new ArrayList<>();
        while (true) {
            if (pageIndex >= MAX_PAGES_PER_REQUEST) {
                throw new TbcDbiException(
                    "TBC_DBI_PAGE_LIMIT_EXCEEDED",
                    "TBC DBI returned too many pages. Narrow the date range and retry."
                );
            }
            String response = postSoap(
                config,
                buildAccountMovementsEnvelope(config, currentPassword(config), dateFrom, dateTo, pageIndex, pageSize),
                "\"http://www.mygemini.com/schemas/mygemini/GetAccountMovements\""
            );
            ParsedMovements page = parseMovements(response, config);
            all.addAll(page.transactions());
            if (page.rawCount() < pageSize) {
                return all;
            }
            pageIndex++;
        }
    }

    public String changePassword(String otp, String newPassword, String currentPasswordOverride) {
        BankApiProperties.Tbc config = properties.getTbc();
        String currentPassword = firstNonBlank(currentPasswordOverride, currentPassword(config));
        validateConfig(config, false);
        require(currentPassword, "Current TBC DBI password");
        validatePasswordChangeInput(otp, newPassword, currentPassword);
        String response = postSoap(
            config,
            buildChangePasswordEnvelope(config, currentPassword, otp, newPassword),
            "\"http://www.mygemini.com/schemas/mygemini/ChangePassword\""
        );
        runtimePassword = newPassword;
        String message = firstResponseText(response, "message", "Message");
        return message.isBlank() ? "TBC DBI password changed successfully." : message;
    }

    private String postSoap(BankApiProperties.Tbc config, String envelope, String soapAction) {
        try {
            HttpClient client = HttpClient.newBuilder()
                .sslContext(buildSslContext(config))
                .connectTimeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .build();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.getEndpoint()))
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", soapAction)
                .POST(HttpRequest.BodyPublishers.ofString(envelope))
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            SoapFault fault = extractSoapFault(body);
            if (!fault.message().isBlank()) {
                String code = fault.code().isBlank() ? "TBC_DBI_SOAP_FAULT" : "TBC_" + fault.code();
                if ("CREDENTIALS_MUST_BE_CHANGED".equals(fault.code())) {
                    throw new TbcDbiException(TbcDbiException.PASSWORD_CHANGE_REQUIRED, "TBC DBI password must be changed before fetching movements.");
                }
                throw new TbcDbiException(code, "TBC DBI SOAP fault: " + fault.message());
            }
            if (response.statusCode() >= 400) {
                throw new TbcDbiException("TBC_DBI_HTTP_" + response.statusCode(), "TBC DBI HTTP " + response.statusCode() + ": " + snippet(body));
            }
            return body;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TbcDbiException("TBC_DBI_INTERRUPTED", "TBC DBI request interrupted", exception);
        } catch (TbcDbiException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new TbcDbiException("TBC_DBI_REQUEST_FAILED", "Failed to call TBC DBI: " + exception.getMessage(), exception);
        }
    }

    private SSLContext buildSslContext(BankApiProperties.Tbc config) throws Exception {
        char[] password = config.getCertificatePassword().toCharArray();
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (var input = certificateInput(config)) {
            keyStore.load(input, password);
        }
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);
        return sslContext;
    }

    String buildAccountMovementsEnvelope(
        BankApiProperties.Tbc config,
        String password,
        LocalDate dateFrom,
        LocalDate dateTo,
        int pageIndex,
        int pageSize
    ) {
        String from = dateFrom.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String to = dateTo.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String nonce = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <soapenv:Envelope
              xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:myg="http://www.mygemini.com/schemas/mygemini"
              xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
              <soapenv:Header>
                <wsse:Security>
                  <wsse:UsernameToken>
                    <wsse:Username>%s</wsse:Username>
                    <wsse:Password>%s</wsse:Password>
                    <wsse:Nonce>%s</wsse:Nonce>
                  </wsse:UsernameToken>
                </wsse:Security>
              </soapenv:Header>
              <soapenv:Body>
                <myg:GetAccountMovementsRequestIo>
                  <myg:accountMovementFilterIo>
                    <myg:pager>
                      <myg:pageIndex>%d</myg:pageIndex>
                      <myg:pageSize>%d</myg:pageSize>
                    </myg:pager>
                    <myg:accountNumber>%s</myg:accountNumber>
                    <myg:accountCurrencyCode>%s</myg:accountCurrencyCode>
                    <myg:periodFrom>%s</myg:periodFrom>
                    <myg:periodTo>%s</myg:periodTo>
                  </myg:accountMovementFilterIo>
                </myg:GetAccountMovementsRequestIo>
              </soapenv:Body>
            </soapenv:Envelope>
            """.formatted(
                xml(config.getUsername()),
                xml(password),
                xml(nonce),
                pageIndex,
                pageSize,
                xml(config.getAccountNumber()),
                xml(config.getCurrency()),
                from,
                to
            );
    }

    private String buildChangePasswordEnvelope(
        BankApiProperties.Tbc config,
        String currentPassword,
        String otp,
        String newPassword
    ) {
        String nonceElement = isBlank(otp) ? "" : "<wsse:Nonce>%s</wsse:Nonce>".formatted(xml(otp));
        return """
            <?xml version="1.0" encoding="utf-8"?>
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/"
              xmlns:myg="http://www.mygemini.com/schemas/mygemini"
              xmlns:wsse="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd">
              <soapenv:Header>
                <wsse:Security>
                  <wsse:UsernameToken>
                    <wsse:Username>%s</wsse:Username>
                    <wsse:Password>%s</wsse:Password>
                    %s
                  </wsse:UsernameToken>
                </wsse:Security>
              </soapenv:Header>
              <soapenv:Body>
                <myg:ChangePasswordRequestIo>
                  <myg:newPassword>%s</myg:newPassword>
                </myg:ChangePasswordRequestIo>
              </soapenv:Body>
            </soapenv:Envelope>
            """.formatted(
                xml(config.getUsername()),
                xml(currentPassword),
                nonceElement,
                xml(newPassword)
            );
    }

    ParsedMovements parseMovements(String body, BankApiProperties.Tbc config) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
            List<BankTransaction> transactions = new ArrayList<>();
            int rawCount = 0;
            NodeList elements = document.getElementsByTagName("*");
            for (int i = 0; i < elements.getLength(); i++) {
                Element element = (Element) elements.item(i);
                if (!hasChild(element, "amount", "transactionAmount", "operationAmount", "creditAmount", "debitAmount")
                    || !hasChild(element, "debitCredit", "debitcredit", "debCred", "entryType", "movementType")) {
                    continue;
                }
                rawCount++;
                BankTransaction transaction = toTransaction(element, config);
                if (transaction != null) {
                    transactions.add(transaction);
                }
            }
            return new ParsedMovements(transactions, rawCount);
        } catch (Exception exception) {
            throw new IllegalStateException("Could not parse TBC DBI response: " + exception.getMessage(), exception);
        }
    }

    private BankTransaction toTransaction(Element element, BankApiProperties.Tbc config) {
        BigDecimal amount = parseAmount(firstText(element, "amount", "sum", "entryAmount", "transactionAmount", "operationAmount", "creditAmount", "debitAmount"));
        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        String debitCredit = firstText(element, "debitCredit", "debitcredit", "debCred", "entryType", "movementType");
        String direction = parseDirection(debitCredit);
        LocalDate date = parseDate(firstText(element, "docDate", "date", "operationDate", "valueDate"));
        if (date == null || direction.isBlank()) {
            return null;
        }
        String counterparty = firstNonBlank(
            firstText(element, "partnerName", "partner", "counterparty", "recipientName", "payerName", "beneficiaryName", "senderName"),
            firstText(element, "partnerAccount", "recipientAccount", "payerAccount", "beneficiaryAccount", "senderAccount")
        );
        String description = firstText(element, "description", "purpose", "comment", "additionalInfo", "paymentDetails");
        String reference = firstText(element, "documentKey", "docKey", "documentNumber", "reference");
        String currency = firstNonBlank(firstText(element, "currency", "ccy"), config.getCurrency());
        String accountNumber = firstNonBlank(firstText(element, "accountNumber", "account"), config.getAccountNumber());
        return new BankTransaction(
            date,
            direction,
            amount,
            currency,
            accountNumber,
            counterparty,
            firstText(element, "partnerTaxCode", "partnerInn", "counterpartyTaxCode", "counterpartyInn", "taxCode"),
            firstText(element, "partnerAccount", "recipientAccount", "payerAccount", "beneficiaryAccount", "senderAccount"),
            description,
            reference,
            element.getTextContent()
        );
    }

    private SoapFault extractSoapFault(String body) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
            String faultCode = firstTextRecursive(document.getDocumentElement(), "faultcode", "FaultCode", "Code");
            return new SoapFault(normalizeFaultCode(faultCode), firstTextRecursive(document.getDocumentElement(), "faultstring", "FaultString", "Reason", "Text"));
        } catch (Exception ignored) {
            return new SoapFault("", "");
        }
    }

    private String firstResponseText(String body, String... names) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(body)));
            return firstTextRecursive(document.getDocumentElement(), names);
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean hasChild(Element element, String... names) {
        return !firstText(element, names).isBlank();
    }

    private String getElementText(Element element) {
        if (element == null) {
            return "";
        }
        NodeList children = element.getChildNodes();
        boolean hasElementChildren = false;
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element) {
                hasElementChildren = true;
                break;
            }
        }
        if (!hasElementChildren) {
            return element.getTextContent() == null ? "" : element.getTextContent().trim();
        }
        String selfName = localName(element);
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element childElement
                && localName(childElement).equalsIgnoreCase(selfName)) {
                return getElementText(childElement);
            }
        }
        String[] commonTextNames = {"amount", "sum", "value", "text", "amountvalue"};
        for (String name : commonTextNames) {
            for (int i = 0; i < children.getLength(); i++) {
                if (children.item(i) instanceof Element childElement
                    && localName(childElement).equalsIgnoreCase(name)) {
                    return getElementText(childElement);
                }
            }
        }
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element childElement) {
                return getElementText(childElement);
            }
        }
        return element.getTextContent() == null ? "" : element.getTextContent().trim();
    }

    private String firstText(Element element, String... names) {
        List<String> wanted = IntStream.range(0, names.length)
            .mapToObj(index -> names[index].toLowerCase(Locale.ROOT))
            .toList();
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement
                && wanted.contains(localName(childElement).toLowerCase(Locale.ROOT))) {
                return getElementText(childElement);
            }
        }
        return "";
    }

    private String firstTextRecursive(Element element, String... names) {
        List<String> wanted = IntStream.range(0, names.length)
            .mapToObj(index -> names[index].toLowerCase(Locale.ROOT))
            .toList();
        NodeList nodes = element.getElementsByTagName("*");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element childElement
                && wanted.contains(localName(childElement).toLowerCase(Locale.ROOT))) {
                return getElementText(childElement);
            }
        }
        return "";
    }

    private String localName(Element element) {
        return Objects.requireNonNullElse(element.getLocalName(), element.getNodeName());
    }

    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("-?\\d+(?:[.,]\\d+)*").matcher(value);
        if (matcher.find()) {
            String match = matcher.group();
            String normalized = match.replace(",", "");
            try {
                // Sign is preserved: a negative debit is a payment reversal and
                // must net against the original payment instead of adding to it.
                return new BigDecimal(normalized);
            } catch (NumberFormatException exception) {
                return BigDecimal.ZERO;
            }
        }
        return BigDecimal.ZERO;
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return LocalDate.parse(trimmed.substring(0, Math.min(trimmed.length(), 10)));
        } catch (Exception ignored) {
            // Try the less common SOAP date formats below.
        }
        try {
            return OffsetDateTime.parse(trimmed).toLocalDate();
        } catch (Exception ignored) {
        }
        try {
            return LocalDateTime.parse(trimmed).toLocalDate();
        } catch (Exception ignored) {
            return null;
        }
    }

    private String parseDirection(String debitCredit) {
        String value = debitCredit == null ? "" : debitCredit.trim().toLowerCase(Locale.ROOT);
        if (value.equals("1") || value.equals("credit") || value.equals("cr") || value.equals("c")) {
            return BankTransaction.CREDIT;
        }
        if (value.equals("0") || value.equals("debit") || value.equals("dr") || value.equals("d")) {
            return BankTransaction.DEBIT;
        }
        return "";
    }

    private void validateConfig(BankApiProperties.Tbc config, boolean requireConfiguredPassword) {
        if (!config.isEnabled()) {
            throw new IllegalStateException("TBC DBI integration is disabled. Set TBC_DBI_ENABLED=true.");
        }
        require(config.getEndpoint(), "TBC_DBI_ENDPOINT");
        require(config.getUsername(), "TBC_DBI_USERNAME");
        if (requireConfiguredPassword) {
            require(config.getPassword(), "TBC_DBI_PASSWORD");
        }
        if (isBlank(config.getCertificatePath()) && isBlank(config.getCertificateBase64())) {
            throw new IllegalStateException("TBC_DBI_CERTIFICATE_PATH or TBC_DBI_CERTIFICATE_BASE64 is required");
        }
        require(config.getCertificatePassword(), "TBC_DBI_CERTIFICATE_PASSWORD");
        require(config.getAccountNumber(), "TBC_DBI_ACCOUNT_NUMBER");
        require(config.getCurrency(), "TBC_DBI_CURRENCY");
    }

    private void validatePasswordChangeInput(String otp, String newPassword, String currentPassword) {
        if (isBlank(otp)) {
            throw new IllegalArgumentException("TBC Digipass/Nonce code is required for password change.");
        }
        if (isBlank(newPassword)) {
            throw new IllegalArgumentException("New TBC password is required.");
        }
        if (newPassword.length() < 8
            || !newPassword.matches(".*[A-Z].*")
            || !newPassword.matches(".*[a-z].*")
            || !newPassword.matches(".*\\d.*")
            || !newPassword.matches(".*[^A-Za-z0-9].*")) {
            throw new IllegalArgumentException("New TBC password must be at least 8 characters and include uppercase, lowercase, number, and symbol.");
        }
        if (newPassword.contains("&") || newPassword.contains("<")) {
            throw new IllegalArgumentException("New TBC password cannot contain '&' or '<' because TBC rejects those XML characters.");
        }
        if (newPassword.equals(properties.getTbc().getUsername())) {
            throw new IllegalArgumentException("New TBC password cannot be the same as username.");
        }
        if (newPassword.equals(currentPassword)) {
            throw new IllegalArgumentException("New TBC password cannot be the same as the current password.");
        }
    }

    private String currentPassword(BankApiProperties.Tbc config) {
        return isBlank(runtimePassword) ? config.getPassword() : runtimePassword;
    }

    private void require(String value, String name) {
        if (isBlank(value)) {
            throw new IllegalStateException(name + " is required");
        }
    }

    private java.io.InputStream certificateInput(BankApiProperties.Tbc config) throws Exception {
        if (!isBlank(config.getCertificateBase64())) {
            byte[] decoded = Base64.getDecoder().decode(config.getCertificateBase64().trim());
            return new ByteArrayInputStream(decoded);
        }
        return Files.newInputStream(Path.of(config.getCertificatePath()));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private String xml(String value) {
        return value == null ? "" : value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    private String snippet(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private String normalizeFaultCode(String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String normalized = code.trim();
        int colon = normalized.lastIndexOf(':');
        if (colon >= 0 && colon < normalized.length() - 1) {
            normalized = normalized.substring(colon + 1);
        }
        return normalized.trim();
    }

    private record SoapFault(String code, String message) {
    }

    record ParsedMovements(List<BankTransaction> transactions, int rawCount) {
    }
}
