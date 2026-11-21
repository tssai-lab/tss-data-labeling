package com.tss.labeling.review;

import java.math.BigDecimal;
import java.util.*;

/** Counts the same visible answer as the result list, never an arbitrary unresolved candidate. */
final class ConfidenceStatistics {
    private static final String UNRESOLVED = "(\"candidateCount\">1 AND \"selectedResultId\" IS DISTINCT FROM \"resultId\")";
    private static final String AI = "(\"sourceKind\"='AI' AND \"resultId\" IS NOT NULL AND NOT " + UNRESOLVED + ")";
    private static final String SCORED = "(confidence IS NOT NULL AND confidence>=0 AND confidence<=1)";
    private static final List<String> BANDS = List.of("HIGH", "MEDIUM", "LOW", "UNKNOWN");
    private static final Map<String, String> COUNTS = conditions();

    private ConfidenceStatistics() {}

    private static Map<String, String> conditions() {
        var conditions = new LinkedHashMap<String, String>();
        conditions.put("aiResultCount", AI);
        conditions.put("scoredCount", AI + " AND " + SCORED);
        conditions.put("unknownCount", AI + " AND NOT " + SCORED);
        conditions.put("unresolvedSampleCount", UNRESOLVED);
        for (String band : BANDS) {
            conditions.put(band, AI + " AND " + (band.equals("UNKNOWN")
                    ? "NOT " + SCORED : SCORED + " AND band='" + band + "'"));
        }
        for (int i = 0; i < 10; i++) {
            conditions.put("bin" + i, AI + " AND " + SCORED + " AND confidence>=" + edge(i)
                    + " AND confidence" + (i == 9 ? "<=" : "<") + edge(i + 1));
        }
        return conditions;
    }

    static String sqlProjection() {
        var projection = new StringBuilder();
        COUNTS.forEach((key, condition) -> projection.append(",\n count(*) FILTER (WHERE ")
                .append(condition).append(") AS \"__confidence_").append(key).append("\""));
        return projection.toString();
    }

    static Map<String, Object> extractSql(Map<String, Object> totals, UUID policyId) {
        var counts = new LinkedHashMap<String, Long>();
        for (String key : COUNTS.keySet()) {
            counts.put(key, ((Number) totals.remove("__confidence_" + key)).longValue());
        }
        return response(counts, policyId);
    }

    static Map<String, Object> fromRows(List<Map<String, Object>> rows, UUID policyId) {
        var counts = new LinkedHashMap<String, Long>();
        COUNTS.keySet().forEach(key -> counts.put(key, 0L));
        for (var row : rows) {
            if (Boolean.TRUE.equals(row.get("requiresResultSelection"))) {
                increment(counts, "unresolvedSampleCount");
                continue;
            }
            if (!"AI".equals(row.get("sourceKind")) || row.get("resultId") == null) continue;
            increment(counts, "aiResultCount");
            Object value = row.get("confidence");
            double score = value instanceof Number number ? number.doubleValue() : Double.NaN;
            if (!Double.isFinite(score) || score < 0 || score > 1) {
                increment(counts, "unknownCount");
                increment(counts, "UNKNOWN");
                continue;
            }
            increment(counts, "scoredCount");
            // Decimal boundaries match PostgreSQL: 0.3 belongs to [0.3, 0.4), and 1 to the last bin.
            int bin = Math.min(9, BigDecimal.valueOf(score).movePointRight(1).intValue());
            increment(counts, "bin" + bin);
            String band = (String) row.get("band");
            if (band != null && BANDS.contains(band)) increment(counts, band);
        }
        return response(counts, policyId);
    }

    private static void increment(Map<String, Long> counts, String key) {
        counts.put(key, counts.get(key) + 1);
    }

    private static Map<String, Object> response(Map<String, Long> counts, UUID policyId) {
        var result = new LinkedHashMap<String, Object>();
        for (String key : List.of("aiResultCount", "scoredCount", "unknownCount", "unresolvedSampleCount")) {
            result.put(key, counts.get(key));
        }
        result.put("confidencePolicyId", policyId);
        Map<String, Long> bands = null;
        if (policyId != null) {
            bands = new LinkedHashMap<>();
            for (String band : BANDS) bands.put(band, counts.get(band));
        }
        result.put("bandCounts", bands);
        var histogram = new ArrayList<Map<String, Object>>(10);
        for (int i = 0; i < 10; i++) {
            var bin = new LinkedHashMap<String, Object>();
            bin.put("lower", edge(i).doubleValue());
            bin.put("upper", edge(i + 1).doubleValue());
            bin.put("upperInclusive", i == 9);
            bin.put("count", counts.get("bin" + i));
            histogram.add(bin);
        }
        result.put("histogram", histogram);
        return result;
    }

    private static BigDecimal edge(int index) {
        return BigDecimal.valueOf(index, 1);
    }
}
