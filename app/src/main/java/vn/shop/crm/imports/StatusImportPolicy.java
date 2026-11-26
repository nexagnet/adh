package vn.shop.crm.imports;

import vn.shop.crm.shipping.CanonicalStatus;

import java.time.LocalDateTime;

final class StatusImportPolicy {
    enum Decision { APPLY, OLDER, REVIEW }

    private StatusImportPolicy() {
    }

    static Decision decide(CanonicalStatus before, CanonicalStatus after,
                           String currentKey, String incomingKey, LocalDateTime currentAt,
                           LocalDateTime watermark, LocalDateTime deliveredOn, LocalDateTime incomingAt) {
        if (incomingAt != null && (isBefore(incomingAt, watermark) || isBefore(incomingAt, deliveredOn))) {
            return Decision.OLDER;
        }
        if (currentKey.equals(incomingKey) && before == after) {
            return Decision.APPLY;
        }
        if (incomingAt != null && incomingAt.equals(currentAt)) {
            return Decision.REVIEW;
        }
        if (incomingAt != null && currentAt != null && incomingAt.isAfter(currentAt)) {
            return Decision.APPLY;
        }
        return regresses(before, after) ? Decision.REVIEW : Decision.APPLY;
    }

    private static boolean isBefore(LocalDateTime value, LocalDateTime baseline) {
        return baseline != null && value.isBefore(baseline);
    }

    private static boolean regresses(CanonicalStatus before, CanonicalStatus after) {
        if (before == after) {
            return false;
        }
        return switch (before) {
            case UNKNOWN, CREATED -> false;
            case IN_TRANSIT -> after == CanonicalStatus.CREATED || after == CanonicalStatus.UNKNOWN;
            case DELIVERED -> after != CanonicalStatus.RETURNING && after != CanonicalStatus.RETURNED;
            case RETURNING -> after != CanonicalStatus.RETURNED;
            case RETURNED, CANCELLED -> true;
        };
    }
}
