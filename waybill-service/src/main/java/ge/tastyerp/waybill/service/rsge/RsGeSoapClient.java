package ge.tastyerp.waybill.service.rsge;

import ge.tastyerp.common.exception.ExternalServiceException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * SOAP Client for RS.ge Waybill Service.
 *
 * This is the Spring Boot equivalent of the legacy TypeScript soapClient.ts.
 * Implements the EXACT same retry logic:
 * - -101 → missing seller_un_id → retry with seller ID
 * - -1064 → date range too large → split into 72h chunks
 *
 * Improvements:
 * - Parallel fetching for chunks
 * - Robust XML escaping
 */
@Slf4j
@Component
public class RsGeSoapClient {

    private static final String NS = "http://tempuri.org/";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    
    // Legacy logic used 72 hours
    private static final int CHUNK_DAYS = 3; 

    @Value("${rsge.endpoint}")
    private String endpoint;

    @Value("${rsge.username}")
    private String username;

    @Value("${rsge.password}")
    private String password;

    @Value("${rsge.timeout-seconds:120}")
    private int timeoutSeconds;

    @Value("${rsge.debug:false}")
    private boolean debugEnabled;

    @Value("${rsge.debug-sample-count:3}")
    private int debugSampleCount;

    @Value("${rsge.debug-response-snippet-length:0}")
    private int debugResponseSnippetLength;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    /**
     * Get waybills from RS.ge.
     * Automatically handles date range chunking if needed.
     */
    public List<Map<String, Object>> getWaybills(LocalDate startDate, LocalDate endDate) {
        log.info("Fetching waybills from RS.ge: {} to {}", startDate, endDate);

        String startStr = startDate.atStartOfDay().format(DATE_FORMAT);
        String endStr = endDate.plusDays(1).atStartOfDay().format(DATE_FORMAT);

        Map<String, String> params = new HashMap<>();
        params.put("create_date_s", startStr);
        params.put("create_date_e", endStr);

        try {
            return callSoapWithRetry("get_waybills", params);
        } catch (Exception e) {
            log.error("Failed to fetch waybills: {}", e.getMessage());
            throw new ExternalServiceException("RS.ge", e.getMessage(), e);
        }
    }

    /**
     * Get buyer waybills from RS.ge (purchase waybills from our perspective).
     * Operation name matches legacy: get_buyer_waybills.
     */
    public List<Map<String, Object>> getBuyerWaybills(LocalDate startDate, LocalDate endDate) {
        log.info("Fetching buyer waybills from RS.ge: {} to {}", startDate, endDate);

        String startStr = startDate.atStartOfDay().format(DATE_FORMAT);
        String endStr = endDate.plusDays(1).atStartOfDay().format(DATE_FORMAT);

        Map<String, String> params = new HashMap<>();
        params.put("create_date_s", startStr);
        params.put("create_date_e", endStr);

        try {
            return callSoapWithRetry("get_buyer_waybills", params);
        } catch (Exception e) {
            log.error("Failed to fetch buyer waybills: {}", e.getMessage());
            throw new ExternalServiceException("RS.ge", e.getMessage(), e);
        }
    }
    /**
     * Call SOAP operation with retry logic.
     */
    private List<Map<String, Object>> callSoapWithRetry(String operation, Map<String, String> params) throws Exception {
        // Add credentials
        params.put("su", username);
        params.put("sp", password);

        // Extract seller ID from username (format: username:seller_id)
        String sellerId = "";
        if (username.contains(":")) {
            sellerId = username.split(":")[1];
        }
        params.put("seller_un_id", sellerId);

        // Build and send request (do NOT log credentials)
        log.info("RS.ge SOAP call operation={} create_date_s={} create_date_e={}",
                operation, params.get("create_date_s"), params.get("create_date_e"));
        String response = sendSoapRequest(operation, params);

        // Parse response
        Map<String, Object> result = parseSoapResponse(response, operation);

        // Check status code
        int statusCode = getStatusCode(result);
        log.info("RS.ge SOAP operation={} status={}", operation, statusCode);

        // Handle -101: missing seller_un_id (retry once with a fallback seller id)
        if (statusCode == -101) {
            String existingSellerUnId = params.get("seller_un_id");
            if (existingSellerUnId == null || existingSellerUnId.isBlank()) {
                String fallbackSellerId = username != null ? username.trim() : "";
                if (!fallbackSellerId.isBlank()) {
                    log.warn("RS.ge returned -101; retrying with fallback seller_un_id");
                    params.put("seller_un_id", fallbackSellerId);
                    String retryResponse = sendSoapRequest(operation, params);
                    Map<String, Object> retryResult = parseSoapResponse(retryResponse, operation);
                    int retryStatus = getStatusCode(retryResult);
                    if (retryStatus == -101) {
                        throw new ExternalServiceException("RS.ge", "Missing seller credentials");
                    }
                    List<Map<String, Object>> extracted = extractWaybillsDeep(retryResult);
                    log.info("RS.ge SOAP operation={} extractedWaybills={} (after -101 retry)", operation, extracted.size());
                    if (debugEnabled) {
                        logDebugSamples(operation, extracted);
                    }
                    return extracted;
                }
            }

            log.warn("RS.ge returned -101, seller_un_id might be missing");
            throw new ExternalServiceException("RS.ge", "Missing seller credentials");
        }

        // Handle -1064: date range too large - split into chunks
        if (statusCode == -1064) {
            log.info("Date range too large, splitting into chunks");
            return fetchInChunks(operation, params);
        }

        // Extract waybills (legacy-compatible deep traversal)
        List<Map<String, Object>> extracted = extractWaybillsDeep(result);
        log.info("RS.ge SOAP operation={} extractedWaybills={}", operation, extracted.size());
        if (debugEnabled) {
            if (extracted.isEmpty()) {
                log.debug("RS.ge SOAP operation={} resultKeys={}", operation, result.keySet());
            }
            logDebugSamples(operation, extracted);
        }
        return extracted;
    }

    /**
     * Fetch waybills in 72-hour chunks in parallel.
     */
    private List<Map<String, Object>> fetchInChunks(String operation, Map<String, String> originalParams) {
        LocalDate startInclusive = LocalDate.parse(originalParams.get("create_date_s").substring(0, 10));
        LocalDate endExclusive = LocalDate.parse(originalParams.get("create_date_e").substring(0, 10));

        if (!endExclusive.isAfter(startInclusive)) {
            return List.of();
        }

        LocalDate endInclusive = endExclusive.minusDays(1);

        List<CompletableFuture<List<Map<String, Object>>>> futures = new ArrayList<>();

        LocalDate chunkStart = startInclusive;
        while (!chunkStart.isAfter(endInclusive)) {
            LocalDate chunkEndInclusive = chunkStart.plusDays(CHUNK_DAYS - 1L);
            if (chunkEndInclusive.isAfter(endInclusive)) {
                chunkEndInclusive = endInclusive;
            }

            final LocalDate s = chunkStart;
            final LocalDate e = chunkEndInclusive;

            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    Map<String, String> chunkParams = new HashMap<>(originalParams);
                    chunkParams.put("create_date_s", s.atStartOfDay().format(DATE_FORMAT));
                    // RS.ge uses an exclusive end timestamp (legacy behavior used endDate+1).
                    chunkParams.put("create_date_e", e.plusDays(1).atStartOfDay().format(DATE_FORMAT));

                    log.debug("Fetching chunk: {} to {}", s, e);

                    String response = sendSoapRequest(operation, chunkParams);
                    Map<String, Object> result = parseSoapResponse(response, operation);
                    int statusCode = getStatusCode(result);
                    if (statusCode != 0 && statusCode != 1) {
                        log.warn("RS.ge SOAP operation={} chunk {}..{} status={}", operation, s, e, statusCode);
                    }
                    List<Map<String, Object>> extracted = extractWaybillsDeep(result);
                    if (debugEnabled) {
                        logDebugSamples(operation, extracted);
                    }
                    return extracted;
                } catch (Exception ex) {
                    log.error("Error fetching chunk {} to {}: {}", s, e, ex.getMessage());
                    throw new RuntimeException(ex);
                }
            }));

            chunkStart = chunkEndInclusive.plusDays(1);
        }

        // Wait for all and collect results
        return futures.stream()
                .map(CompletableFuture::join)
                .flatMap(List::stream)
                .collect(Collectors.toList());
    }

    /**
     * Send SOAP request to RS.ge.
     */
    private String sendSoapRequest(String operation, Map<String, String> params) throws Exception {
        String soapBody = buildSoapEnvelope(operation, params);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"" + NS + operation + "\"")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .POST(HttpRequest.BodyPublishers.ofString(soapBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 500) {
            throw new ExternalServiceException("RS.ge",
                    "HTTP " + response.statusCode() + ": " + response.body());
        }

        if (debugEnabled && debugResponseSnippetLength > 0 && response.statusCode() == 500) {
            log.debug("RS.ge SOAP operation={} HTTP 500 response snippet={}",
                    operation, snippet(response.body(), debugResponseSnippetLength));
        }

        return response.body();
    }

    /**
     * Build SOAP envelope.
     */
    private String buildSoapEnvelope(String operation, Map<String, String> params) {
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            body.append(String.format("<%s>%s</%s>",
                    entry.getKey(), xmlEscape(entry.getValue()), entry.getKey()));
        }

        return """
                <?xml version="1.0" encoding="utf-8"?>
                <soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                               xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                               xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
                  <soap:Body>
                    <%s xmlns="%s">
                      %s
                    </%s>
                  </soap:Body>
                </soap:Envelope>
                """.formatted(operation, NS, body.toString(), operation);
    }

    /**
     * Parse SOAP response.
     */
    private Map<String, Object> parseSoapResponse(String xml, String operation) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        hardenXmlFactory(factory);
        factory.setNamespaceAware(false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(new InputSource(new StringReader(xml)));

        // Check for SOAP Fault
        NodeList faults = doc.getElementsByTagName("faultstring");
        if (faults.getLength() > 0) {
            throw new ExternalServiceException("RS.ge", faults.item(0).getTextContent());
        }

        // Find result node
        NodeList results = doc.getElementsByTagName(operation + "Result");
        if (results.getLength() == 0) {
            if (debugEnabled && debugResponseSnippetLength > 0) {
                log.debug("RS.ge SOAP operation={} missing Result node; xml snippet={}",
                        operation, snippet(xml, debugResponseSnippetLength));
            }
            return new HashMap<>();
        }

        return nodeToMap(results.item(0));
    }

    private void hardenXmlFactory(DocumentBuilderFactory factory) {
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
        } catch (Exception e) {
            // Best-effort hardening; keep parsing if the XML implementation does not support these features.
            log.warn("XML parser hardening not fully supported: {}", e.getMessage());
        }
    }

    /**
     * Convert XML node to Map.
     */
    private Map<String, Object> nodeToMap(Node node) {
        Map<String, Object> map = new HashMap<>();
        NodeList children = node.getChildNodes();

        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() != Node.ELEMENT_NODE) continue;

            String name = child.getNodeName();
            NodeList grandchildren = child.getChildNodes();

            if (grandchildren.getLength() == 1 && grandchildren.item(0).getNodeType() == Node.TEXT_NODE) {
                // Simple value
                map.put(name, child.getTextContent());
            } else {
                // Complex value or array
                Object existing = map.get(name);
                Map<String, Object> childMap = nodeToMap(child);

                if (existing instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> list = (List<Map<String, Object>>) existing;
                    list.add(childMap);
                } else if (existing != null) {
                    List<Object> list = new ArrayList<>();
                    list.add(existing);
                    list.add(childMap);
                    map.put(name, list);
                } else {
                    map.put(name, childMap);
                }
            }
        }

        return map;
    }

    /**
     * Get status code from result.
     */
    private int getStatusCode(Map<String, Object> result) {
        Object status = result.get("STATUS");
        if (status == null) {
            status = result.get("RESULT");
            if (status instanceof Map) {
                status = ((Map<?, ?>) status).get("STATUS");
            }
        }
        if (status == null) return 0;

        try {
            return Integer.parseInt(status.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Extract waybills from result.
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractWaybillsDeep(Map<String, Object> root) {
        Object unwrapped = unwrapResult(root);

        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        ArrayDeque<Object> queue = new ArrayDeque<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        queue.add(unwrapped);

        while (!queue.isEmpty()) {
            Object cur = queue.poll();
            if (cur == null) continue;

            if (cur instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map || item instanceof List) {
                        queue.add(item);
                    }
                }
                continue;
            }

            if (!(cur instanceof Map<?, ?>)) {
                continue;
            }

            if (!visited.add(cur)) {
                continue;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) cur;

            if (isWaybillCandidate(map)) {
                String id = firstNonBlank(map, "ID", "id", "waybill_id", "waybillId");
                if (id == null) {
                    id = "unknown_" + byId.size();
                }
                Map<String, Object> existing = byId.get(id);
                if (existing == null) {
                    byId.put(id, map);
                } else {
                    byId.put(id, chooseRicherWaybill(existing, map));
                }
            }

            // Container patterns (legacy semantics)
            pushContainer(queue, map.get("WAYBILL_LIST"));
            pushContainer(queue, map.get("WAYBILL"));
            pushContainer(queue, map.get("BUYER_WAYBILL"));
            pushContainer(queue, map.get("PURCHASE_WAYBILL"));

            // Traverse children
            for (Object value : map.values()) {
                if (value instanceof Map || value instanceof List) {
                    queue.add(value);
                }
            }
        }

        return new ArrayList<>(byId.values());
    }

    /**
     * RS.ge responses can contain the same waybill object multiple times (sometimes shallow, sometimes detailed).
     * Prefer the "richer" map so VAT calculations don't drop nested/embedded waybills whose amounts only appear
     * in the deeper representation.
     */
    private Map<String, Object> chooseRicherWaybill(Map<String, Object> a, Map<String, Object> b) {
        int scoreA = waybillCompletenessScore(a);
        int scoreB = waybillCompletenessScore(b);
        if (scoreB > scoreA) return b;
        if (scoreA > scoreB) return a;

        // Tie-breaker: keep the larger map (more fields)
        int sizeA = a != null ? a.size() : 0;
        int sizeB = b != null ? b.size() : 0;
        return sizeB > sizeA ? b : a;
    }

    private int waybillCompletenessScore(Map<String, Object> map) {
        if (map == null || map.isEmpty()) return 0;
        int score = 0;

        // Amount presence is critical for VAT; weight it heavily.
        if (hasNonBlank(map,
                "FULL_AMOUNT", "full_amount",
                "TOTAL_AMOUNT", "total_amount",
                "GROSS_AMOUNT", "gross_amount",
                "NET_AMOUNT", "net_amount",
                "AMOUNT_LARI", "amount_lari",
                "AMOUNT", "amount",
                "SUM", "sum",
                "SUMA", "suma",
                "VALUE", "value",
                "VALUE_LARI", "value_lari")) {
            score += 20;
        }

        if (hasNonBlank(map, "CREATE_DATE", "create_date", "WAYBILL_DATE", "waybill_date", "DATE", "date")) {
            score += 8;
        }

        if (hasNonBlank(map, "BUYER_TIN", "buyer_tin")) score += 3;
        if (hasNonBlank(map, "SELLER_TIN", "seller_tin")) score += 3;
        if (hasNonBlank(map, "STATUS", "status")) score += 1;

        // Slight preference for maps with more fields overall.
        score += Math.min(map.size(), 50) / 5;
        return score;
    }

    private boolean hasNonBlank(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v == null) continue;
            String s = v.toString().trim();
            if (!s.isEmpty()) return true;
        }
        return false;
    }

    private Object unwrapResult(Map<String, Object> result) {
        // Many RS.ge responses include RESULT wrapper node
        Object inner = result.get("RESULT");
        if (inner instanceof Map) {
            return inner;
        }
        return result;
    }

    private void pushContainer(ArrayDeque<Object> queue, Object candidate) {
        if (candidate == null) return;
        if (candidate instanceof Map<?, ?> map) {
            // WAYBILL_LIST commonly wraps WAYBILL array
            Object inner = ((Map<?, ?>) map).get("WAYBILL");
            queue.add(inner != null ? inner : map);
            return;
        }
        queue.add(candidate);
    }

    private boolean isWaybillCandidate(Map<String, Object> map) {
        // Must have an ID and at least one waybill-ish field
        boolean hasId = firstNonBlank(map, "ID", "id", "waybill_id", "waybillId") != null;
        if (!hasId) return false;

        return map.containsKey("FULL_AMOUNT") || map.containsKey("full_amount")
                || map.containsKey("TOTAL_AMOUNT") || map.containsKey("total_amount")
                || map.containsKey("GROSS_AMOUNT") || map.containsKey("gross_amount")
                || map.containsKey("NET_AMOUNT") || map.containsKey("net_amount")
                || map.containsKey("AMOUNT_LARI") || map.containsKey("amount_lari")
                || map.containsKey("AMOUNT") || map.containsKey("amount")
                || map.containsKey("BUYER_TIN") || map.containsKey("buyer_tin")
                || map.containsKey("SELLER_TIN") || map.containsKey("seller_tin")
                || map.containsKey("STATUS") || map.containsKey("status")
                || map.containsKey("CREATE_DATE") || map.containsKey("create_date");
    }

    private String firstNonBlank(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            Object v = map.get(k);
            if (v == null) continue;
            String s = v.toString().trim();
            if (!s.isEmpty()) return s;
        }
        return null;
    }

    private void logDebugSamples(String operation, List<Map<String, Object>> waybills) {
        int limit = Math.min(Math.max(debugSampleCount, 0), waybills.size());
        for (int i = 0; i < limit; i++) {
            Map<String, Object> wb = waybills.get(i);
            String id = firstNonBlank(wb, "ID", "id", "waybill_id", "waybillId");
            Object date = wb.getOrDefault("CREATE_DATE", wb.getOrDefault("create_date", null));
            Object status = wb.getOrDefault("STATUS", wb.getOrDefault("status", null));
            Object amount = wb.getOrDefault("FULL_AMOUNT", wb.getOrDefault("full_amount", wb.getOrDefault("TOTAL_AMOUNT", null)));
            Object buyerTin = wb.getOrDefault("BUYER_TIN", wb.getOrDefault("buyer_tin", null));
            Object sellerTin = wb.getOrDefault("SELLER_TIN", wb.getOrDefault("seller_tin", null));
            log.debug("RS.ge sample op={} idx={} id={} date={} status={} amount={} buyerTin={} sellerTin={}",
                    operation, i, id, date, status, amount, buyerTin, sellerTin);
        }
    }

    private String snippet(String input, int maxLen) {
        if (input == null) return "";
        String s = input.replace("\r", " ").replace("\n", " ").trim();
        if (maxLen <= 0 || s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "...";
    }

    /**
     * Robust XML escaping.
     */
    private String xmlEscape(String str) {
        if (str == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : str.toCharArray()) {
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&apos;");
                default -> {
                    // Filter out invalid XML control characters (0x00-0x08, 0x0B, 0x0C, 0x0E-0x1F)
                    if ((c >= 0x20) || c == 0x09 || c == 0x0A || c == 0x0D) {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
