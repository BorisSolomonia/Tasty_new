package ge.tastyerp.config.repository;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import ge.tastyerp.common.dto.config.CustomerDto;
import ge.tastyerp.common.util.FutureResults;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

/**
 * Repository for customers stored in Firebase.
 *
 * Data access only - NO business logic here.
 *
 * Structure in Firebase:
 * customers/{customerId}: {
 *   "CustomerName": "შპს წისქვილი ჯგუფი",
 *   "Identification": "202200778",
 *   "ContactInfo": "..."
 * }
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class CustomerRepository {

    private static final String COLLECTION = "customers";

    private final Firestore firestore;

    /**
     * Get all customers.
     */
    /**
     * A store failure throws (503) instead of returning an empty list: the
     * audit-control dashboard derives "is this customer a real entity" from
     * this list, so an empty answer silently reclassified every customer as
     * unreal and moved their sales into the wrong ledger (BOR-81 B-3).
     */
    public List<CustomerDto> findAll() {
        List<CustomerDto> customers = new ArrayList<>();
        for (QueryDocumentSnapshot document : FutureResults.await(
                firestore.collection(COLLECTION).get(), "fetch all customers").getDocuments()) {
            customers.add(mapToDto(document));
        }
        return customers;
    }

    /**
     * Set (upsert) the is_real_entity flag for a customer (BOR-74).
     *
     * The customers collection is keyed by document id = identification, so we
     * merge the field onto that document and create a stub if it is missing.
     */
    public CustomerDto setRealEntity(String identification, boolean isRealEntity) {
        try {
            var docRef = firestore.collection(COLLECTION).document(identification);
            var snapshot = docRef.get().get();

            var update = new java.util.HashMap<String, Object>();
            update.put("Identification", identification);
            update.put("isRealEntity", isRealEntity);
            if (!snapshot.exists() || snapshot.getString("CustomerName") == null) {
                update.put("CustomerName", identification);
            }
            docRef.set(update, com.google.cloud.firestore.SetOptions.merge()).get();

            return findByIdentification(identification)
                    .orElse(CustomerDto.builder()
                            .identification(identification)
                            .customerName(identification)
                            .isRealEntity(isRealEntity)
                            .build());
        } catch (InterruptedException e) {
            // Genuine interruption: preserve the flag for the thread owner.
            Thread.currentThread().interrupt();
            log.error("Error setting isRealEntity for {}: {}", identification, e.getMessage());
            throw new RuntimeException("Failed to update isRealEntity", e);
        } catch (ExecutionException e) {
            // NOT an interruption: never touch the interrupt flag here, or the
            // next blocking call on this thread fails instantly (BOR-81 B-3).
            log.error("Error setting isRealEntity for {}: {}", identification, e.getMessage());
            throw new RuntimeException("Failed to update isRealEntity", e);
        }
    }

    /**
     * Find customer by identification number.
     */
    public Optional<CustomerDto> findByIdentification(String identification) {
        try {
            // orderBy(documentId) makes the limit(1) pick DETERMINISTIC when more
            // than one doc shares the identification (otherwise which one wins is
            // arbitrary, so name/isRealEntity could differ between calls/devices).
            var query = firestore.collection(COLLECTION)
                    .whereEqualTo("Identification", identification)
                    .orderBy(com.google.cloud.firestore.FieldPath.documentId())
                    .limit(1)
                    .get()
                    .get();

            if (query.isEmpty()) {
                return Optional.empty();
            }

            return Optional.of(mapToDto(query.getDocuments().get(0)));

        } catch (InterruptedException e) {
            // Genuine interruption: preserve the flag for the thread owner.
            Thread.currentThread().interrupt();
            log.error("Error fetching customer by identification {}: {}", identification, e.getMessage());
            return Optional.empty();
        } catch (ExecutionException e) {
            // NOT an interruption: never touch the interrupt flag here, or the
            // next blocking call on this thread fails instantly (BOR-81 B-3).
            log.error("Error fetching customer by identification {}: {}", identification, e.getMessage());
            return Optional.empty();
        }
    }

    private CustomerDto mapToDto(QueryDocumentSnapshot document) {
        return CustomerDto.builder()
                .identification(document.getString("Identification"))
                .customerName(document.getString("CustomerName"))
                .contactInfo(document.getString("ContactInfo"))
                .isRealEntity(document.getBoolean("isRealEntity"))
                .build();
    }
}
