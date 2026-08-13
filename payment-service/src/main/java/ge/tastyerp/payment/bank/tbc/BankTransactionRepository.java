package ge.tastyerp.payment.bank.tbc;

import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.WriteBatch;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Repository for raw bank movements (bankTransactions collection) and the
 * per-bank sync watermark (bankSyncState collection).
 *
 * Data access only - NO business logic here.
 *
 * The benchmark implementation dedups bank rows by window replacement, not by
 * key: bank rows carry no time-of-day, so two identical same-day payments are
 * both legitimate and must both survive. replaceWindow() reproduces that —
 * delete everything for the source from the window start, insert the fresh
 * fetch wholesale.
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class BankTransactionRepository {

    private static final String COLLECTION_TRANSACTIONS = "bankTransactions";
    private static final String COLLECTION_SYNC_STATE = "bankSyncState";

    /** Firestore hard limit on operations per WriteBatch. */
    private static final int FIRESTORE_BATCH_LIMIT = 500;

    private final Firestore firestore;

    /**
     * Replace the [windowStart, ∞) window for a source: delete stored rows in
     * the window, then insert the fresh fetch. Dates are stored as ISO strings,
     * so the range filter is a plain string comparison.
     */
    public int replaceWindow(String source, LocalDate windowStart, List<Map<String, Object>> rows) {
        deleteWindow(source, windowStart);
        int written = 0;
        try {
            for (int i = 0; i < rows.size(); i += FIRESTORE_BATCH_LIMIT) {
                WriteBatch batch = firestore.batch();
                List<Map<String, Object>> chunk = rows.subList(i, Math.min(i + FIRESTORE_BATCH_LIMIT, rows.size()));
                for (Map<String, Object> row : chunk) {
                    batch.set(firestore.collection(COLLECTION_TRANSACTIONS).document(), row);
                }
                batch.commit().get();
                written += chunk.size();
            }
        } catch (InterruptedException | ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to write bank transactions for source " + source, e);
        }
        return written;
    }

    /**
     * Upserts rows under caller-supplied document ids.
     *
     * <p>Used by the Excel statement importer, which has a natural key per row
     * (the bank's own transaction id) and so can be re-run over an overlapping
     * file without creating duplicates — unlike the API sync, whose window
     * replacement is the right tool when rows have no stable id.</p>
     *
     * @param rowsById document id -> document body
     * @return the number of rows written
     */
    public int upsertAll(Map<String, Map<String, Object>> rowsById) {
        if (rowsById == null || rowsById.isEmpty()) {
            return 0;
        }
        List<Map.Entry<String, Map<String, Object>>> entries = new ArrayList<>(rowsById.entrySet());
        int written = 0;
        try {
            for (int i = 0; i < entries.size(); i += FIRESTORE_BATCH_LIMIT) {
                WriteBatch batch = firestore.batch();
                List<Map.Entry<String, Map<String, Object>>> chunk =
                        entries.subList(i, Math.min(i + FIRESTORE_BATCH_LIMIT, entries.size()));
                for (Map.Entry<String, Map<String, Object>> entry : chunk) {
                    batch.set(firestore.collection(COLLECTION_TRANSACTIONS).document(entry.getKey()),
                            entry.getValue());
                }
                batch.commit().get();
                written += chunk.size();
            }
        } catch (InterruptedException | ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to upsert bank transactions", e);
        }
        return written;
    }

    private void deleteWindow(String source, LocalDate windowStart) {
        try {
            QuerySnapshot snapshot = firestore.collection(COLLECTION_TRANSACTIONS)
                    .whereEqualTo("source", source)
                    .whereGreaterThanOrEqualTo("date", windowStart.toString())
                    .get().get();
            List<QueryDocumentSnapshot> docs = snapshot.getDocuments();
            for (int i = 0; i < docs.size(); i += FIRESTORE_BATCH_LIMIT) {
                WriteBatch batch = firestore.batch();
                for (QueryDocumentSnapshot doc : docs.subList(i, Math.min(i + FIRESTORE_BATCH_LIMIT, docs.size()))) {
                    batch.delete(doc.getReference());
                }
                batch.commit().get();
            }
            if (!docs.isEmpty()) {
                log.info("Replaced {} stored {} bank rows from {} onward", docs.size(), source, windowStart);
            }
        } catch (InterruptedException | ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to clear bank transaction window for source " + source, e);
        }
    }

    public Map<String, Object> getSyncState(String source) {
        try {
            DocumentSnapshot doc = firestore.collection(COLLECTION_SYNC_STATE).document(source).get().get();
            return doc.exists() ? doc.getData() : Map.of();
        } catch (InterruptedException | ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            // A financial system must never report an outage as empty state —
            // callers treat missing state as "never synced" and would backfill.
            throw new RuntimeException("Failed to read bank sync state for source " + source, e);
        }
    }

    public void saveSyncState(String source, Map<String, Object> state) {
        try {
            firestore.collection(COLLECTION_SYNC_STATE).document(source).set(state).get();
        } catch (InterruptedException | ExecutionException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new RuntimeException("Failed to save bank sync state for source " + source, e);
        }
    }
}
