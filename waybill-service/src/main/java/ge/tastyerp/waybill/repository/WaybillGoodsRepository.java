package ge.tastyerp.waybill.repository;

import com.google.api.core.ApiFuture;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.WriteBatch;
import ge.tastyerp.common.dto.waybill.WaybillGoodDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Durable store for a waybill's goods lines.
 *
 * <h3>Why this exists</h3>
 * <p>Line items were previously fetched from RS.ge with one {@code get_waybill}
 * SOAP call <b>per waybill, on every request</b>, behind only a three-minute
 * in-memory cache. On the deployed VM a cold cache made the audit endpoints take
 * 147s for eight months of data and time out entirely over three years — the
 * reverse proxy returned 502 while the service was still fetching.</p>
 *
 * <p>A historical waybill's goods do not change, so re-fetching them is pure
 * waste. They are read once and kept. Records are written even when a waybill
 * has no goods, so an empty result is not re-fetched forever.</p>
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class WaybillGoodsRepository {

    private static final String COLLECTION = "waybill_goods";

    /** Firestore hard limit on operations per WriteBatch. */
    private static final int BATCH_LIMIT = 500;

    /** Firestore getAll is fine with large fan-outs, but keep requests bounded. */
    private static final int READ_CHUNK = 300;

    private final Firestore firestore;

    /** Goods and the return flag for one waybill, as stored. */
    public record StoredGoods(List<WaybillGoodDto> goods, boolean returnWaybill) {
    }

    /**
     * @return only the waybill ids that are already stored; missing ids are
     *         simply absent from the map
     */
    public Map<String, StoredGoods> findByWaybillIds(List<String> waybillIds) {
        Map<String, StoredGoods> result = new LinkedHashMap<>();
        if (waybillIds == null || waybillIds.isEmpty()) {
            return result;
        }
        try {
            // Chunks are issued together rather than one after another: three and a
            // half years is ~33 chunks, and serialising them turns a fast read into
            // a slow one for no reason.
            List<ApiFuture<List<DocumentSnapshot>>> pending = new ArrayList<>();
            for (int i = 0; i < waybillIds.size(); i += READ_CHUNK) {
                List<String> chunk = waybillIds.subList(i, Math.min(i + READ_CHUNK, waybillIds.size()));
                DocumentReference[] refs = chunk.stream()
                        .map(id -> firestore.collection(COLLECTION).document(id))
                        .toArray(DocumentReference[]::new);
                pending.add(firestore.getAll(refs));
            }
            for (ApiFuture<List<DocumentSnapshot>> future : pending) {
                for (DocumentSnapshot doc : future.get()) {
                    if (doc.exists()) {
                        result.put(doc.getId(), toStoredGoods(doc));
                    }
                }
            }
        } catch (InterruptedException e) {
            // Genuine interruption: preserve the flag for the thread owner.
            Thread.currentThread().interrupt();
            // A cache read failing must not fail the request — the caller falls
            // back to RS.ge, which is slower but correct.
            log.error("Error reading stored waybill goods: {}", e.getMessage());
        } catch (ExecutionException e) {
            // NOT an interruption: never touch the interrupt flag here, or the
            // next blocking call on this thread fails instantly (BOR-81 B-3).
            // A cache read failing must not fail the request — the caller falls
            // back to RS.ge, which is slower but correct.
            log.error("Error reading stored waybill goods: {}", e.getMessage());
        }
        return result;
    }

    public int saveAll(Map<String, StoredGoods> byWaybillId) {
        if (byWaybillId == null || byWaybillId.isEmpty()) {
            return 0;
        }
        List<Map.Entry<String, StoredGoods>> entries = new ArrayList<>(byWaybillId.entrySet());
        String fetchedAt = LocalDateTime.now().toString();
        int written = 0;
        try {
            for (int i = 0; i < entries.size(); i += BATCH_LIMIT) {
                WriteBatch batch = firestore.batch();
                for (Map.Entry<String, StoredGoods> entry : entries.subList(
                        i, Math.min(i + BATCH_LIMIT, entries.size()))) {
                    batch.set(firestore.collection(COLLECTION).document(entry.getKey()),
                            toMap(entry.getKey(), entry.getValue(), fetchedAt));
                }
                batch.commit().get();
                written += Math.min(BATCH_LIMIT, entries.size() - i);
            }
            log.info("Stored goods for {} waybills", written);
        } catch (InterruptedException e) {
            // Genuine interruption: preserve the flag for the thread owner.
            Thread.currentThread().interrupt();
            // Persisting is an optimisation; failing to persist must not fail the
            // request that already has the data in hand.
            log.error("Error storing waybill goods: {}", e.getMessage());
        } catch (ExecutionException e) {
            // NOT an interruption: never touch the interrupt flag here, or the
            // next blocking call on this thread fails instantly (BOR-81 B-3).
            // Persisting is an optimisation; failing to persist must not fail the
            // request that already has the data in hand.
            log.error("Error storing waybill goods: {}", e.getMessage());
        }
        return written;
    }

    private static Map<String, Object> toMap(String waybillId, StoredGoods stored, String fetchedAt) {
        List<Map<String, Object>> goods = new ArrayList<>();
        if (stored.goods() != null) {
            for (WaybillGoodDto g : stored.goods()) {
                Map<String, Object> m = new HashMap<>();
                m.put("name", g.getName());
                m.put("quantity", toDouble(g.getQuantity()));
                m.put("unit", g.getUnit());
                m.put("unitPrice", toDouble(g.getUnitPrice()));
                m.put("totalPrice", toDouble(g.getTotalPrice()));
                goods.add(m);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("waybillId", waybillId);
        data.put("goods", goods);
        data.put("returnWaybill", stored.returnWaybill());
        data.put("fetchedAt", fetchedAt);
        return data;
    }

    @SuppressWarnings("unchecked")
    private static StoredGoods toStoredGoods(DocumentSnapshot doc) {
        List<WaybillGoodDto> goods = new ArrayList<>();
        Object raw = doc.get("goods");
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> m = (Map<String, Object>) map;
                    goods.add(WaybillGoodDto.builder()
                            .name(str(m.get("name")))
                            .quantity(num(m.get("quantity")))
                            .unit(str(m.get("unit")))
                            .unitPrice(num(m.get("unitPrice")))
                            .totalPrice(num(m.get("totalPrice")))
                            .build());
                }
            }
        }
        return new StoredGoods(goods, Boolean.TRUE.equals(doc.getBoolean("returnWaybill")));
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal num(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
