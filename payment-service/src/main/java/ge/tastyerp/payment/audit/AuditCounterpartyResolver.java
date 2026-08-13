package ge.tastyerp.payment.audit;

import ge.tastyerp.common.dto.auditlayer.CounterpartyAliasDto;
import ge.tastyerp.common.dto.auditlayer.CounterpartyIdentitySource;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Establishes who a bank row's counterparty is when the tax-code column is blank.
 *
 * <h3>Why this is necessary</h3>
 * <p>Real statements routinely name a counterparty without printing its tax code.
 * In the 2023–2026 TBC export, 2,273 outflow rows worth ₾7,467,982 carry no tax
 * code — but ₾5,032,479 of that names a counterparty whose code appears on
 * <em>other</em> rows, ₾4,553,024 of it a single documented supplier. Treating
 * those as unidentified understated payments to real suppliers by millions and
 * inflated the uncovered purchase balance by the same amount.</p>
 *
 * <h3>How identity is established, in order of strength</h3>
 * <ol>
 *   <li>the statement printed a tax code — {@link CounterpartyIdentitySource#DIRECT};</li>
 *   <li>a person taught this name — {@link CounterpartyIdentitySource#MANUAL_ALIAS};</li>
 *   <li>the same normalised name carries a tax code elsewhere in the operator's
 *       own data — {@link CounterpartyIdentitySource#RESOLVED_BY_NAME}.</li>
 * </ol>
 *
 * <h3>What it refuses to do</h3>
 * <p>A name that maps to more than one tax code is <b>never</b> resolved. It is
 * marked {@link CounterpartyIdentitySource#AMBIGUOUS} and stays out of every
 * per-counterparty total, because picking the more frequent code would silently
 * attribute money to the wrong party — the precise failure this module exists to
 * detect. Matching is exact-after-normalisation for the same reason: fuzzy
 * matching would trade a visible gap for an invisible error.</p>
 *
 * <p>Nothing here writes to a source row. Resolution is metadata carried
 * alongside, and every resolved row reports the basis it was resolved on.</p>
 */
@Slf4j
public final class AuditCounterpartyResolver {

    private final Map<String, String> nameToTin;
    private final Map<String, Set<String>> ambiguousNames;
    private final Map<String, String> manualAliases;
    private final Map<String, String> basisByName;

    private AuditCounterpartyResolver(Map<String, String> nameToTin,
                                      Map<String, Set<String>> ambiguousNames,
                                      Map<String, String> manualAliases,
                                      Map<String, String> basisByName) {
        this.nameToTin = nameToTin;
        this.ambiguousNames = ambiguousNames;
        this.manualAliases = manualAliases;
        this.basisByName = basisByName;
    }

    /**
     * Normalised matching key for a counterparty name.
     *
     * <p>Statements append qualifiers after a comma or semicolon — "შპს მაგსი,
     * 405135946", "შპს ხორცი 2022;  არსენ გაგნიძე 57001009022" — so the key is
     * the leading segment, whitespace-collapsed and lower-cased. Deliberately
     * conservative: digits are not stripped, because two people whose names
     * differ only by a trailing number are two people.</p>
     */
    public static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String head = name.split("[,;]", 2)[0];
        String collapsed = head.trim().replaceAll("\\s+", " ");
        return collapsed.isEmpty() ? null : collapsed.toLowerCase(Locale.ROOT);
    }

    /**
     * @param observed  name→tax-code pairs seen in the operator's own data (bank
     *                  rows that do carry a code, RS.ge counterparties)
     * @param aliases   links a person taught explicitly; these win over observed
     *                  evidence and are never treated as ambiguous
     */
    public static AuditCounterpartyResolver build(List<NameTin> observed,
                                                  List<CounterpartyAliasDto> aliases) {
        Map<String, Set<String>> candidates = new LinkedHashMap<>();
        Map<String, Integer> support = new LinkedHashMap<>();
        if (observed != null) {
            for (NameTin o : observed) {
                String key = normalize(o.name());
                String tin = o.tin() == null ? null : o.tin().trim();
                if (key == null || tin == null || tin.isEmpty()) {
                    continue;
                }
                candidates.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(tin);
                support.merge(key + "|" + tin, 1, Integer::sum);
            }
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        Map<String, Set<String>> ambiguous = new LinkedHashMap<>();
        Map<String, String> basis = new LinkedHashMap<>();
        candidates.forEach((key, tins) -> {
            if (tins.size() == 1) {
                String tin = tins.iterator().next();
                resolved.put(key, tin);
                basis.put(key, "name matches " + support.getOrDefault(key + "|" + tin, 1)
                        + " record(s) that do carry tax code " + tin);
            } else {
                ambiguous.put(key, tins);
            }
        });

        Map<String, String> manual = new LinkedHashMap<>();
        if (aliases != null) {
            for (CounterpartyAliasDto alias : aliases) {
                String key = alias.getNormalizedName() != null
                        ? alias.getNormalizedName() : normalize(alias.getRawName());
                if (key != null && alias.getCounterpartyTin() != null) {
                    manual.put(key, alias.getCounterpartyTin().trim());
                }
            }
        }

        if (!ambiguous.isEmpty()) {
            log.info("Counterparty resolution: {} names resolvable, {} ambiguous and left unresolved",
                    resolved.size(), ambiguous.size());
        }
        return new AuditCounterpartyResolver(resolved, ambiguous, manual, basis);
    }

    public static AuditCounterpartyResolver empty() {
        return new AuditCounterpartyResolver(Map.of(), Map.of(), Map.of(), Map.of());
    }

    /**
     * Resolves a row's counterparty.
     *
     * @param printedTin the tax code the statement printed, possibly blank
     * @param name       the counterparty name the statement printed
     */
    public Resolution resolve(String printedTin, String name) {
        String tin = printedTin == null ? null : printedTin.trim();
        if (tin != null && !tin.isEmpty()) {
            return new Resolution(tin, CounterpartyIdentitySource.DIRECT,
                    "tax code printed on the statement row");
        }
        String key = normalize(name);
        if (key == null) {
            return new Resolution(null, CounterpartyIdentitySource.UNRESOLVED,
                    "the row names no counterparty");
        }
        String manual = manualAliases.get(key);
        if (manual != null) {
            return new Resolution(manual, CounterpartyIdentitySource.MANUAL_ALIAS,
                    "an operator linked this name to tax code " + manual);
        }
        Set<String> conflicting = ambiguousNames.get(key);
        if (conflicting != null) {
            return new Resolution(null, CounterpartyIdentitySource.AMBIGUOUS,
                    "this name appears with more than one tax code (" + String.join(", ", conflicting)
                            + "), so it was not resolved");
        }
        String learned = nameToTin.get(key);
        if (learned != null) {
            return new Resolution(learned, CounterpartyIdentitySource.RESOLVED_BY_NAME,
                    basisByName.getOrDefault(key, "name matches records carrying tax code " + learned));
        }
        return new Resolution(null, CounterpartyIdentitySource.UNRESOLVED,
                "no tax code, and this name never appears with one");
    }

    public int resolvableNameCount() {
        return nameToTin.size();
    }

    public Map<String, Set<String>> ambiguousNames() {
        return Map.copyOf(ambiguousNames);
    }

    /** One observed (name, tax code) pair from the operator's own data. */
    public record NameTin(String name, String tin) {
    }

    /** The outcome, with the basis it rests on. */
    public record Resolution(String tin, CounterpartyIdentitySource source, String basis) {
        public boolean isIdentified() {
            return tin != null && !tin.isBlank();
        }
    }
}
