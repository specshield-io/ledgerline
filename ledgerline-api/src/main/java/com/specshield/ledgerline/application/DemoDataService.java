package com.specshield.ledgerline.application;

import com.specshield.ledgerline.domain.Money;
import com.specshield.ledgerline.domain.Order;
import com.specshield.ledgerline.domain.OrderIds;
import com.specshield.ledgerline.domain.OrderStatus;
import com.specshield.ledgerline.domain.OrderStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The public demo's synthetic data: 25 orders across every status ({@code demo-1001} to {@code demo-1025}).
 * Deterministic apart from ids and timestamps, so a reset always produces the same picture. Callers serialise runs
 * with a lock ({@code demo.DemoReset}); this class only guarantees that each run is one transaction.
 */
@Service
public class DemoDataService {

    public static final String MERCHANT_REF_PREFIX = "demo-";
    public static final int SEEDED_ORDERS = 25;
    static final String SEEDER_ACTOR = "system:demo-seeder";

    /** One seeded order: status, amount, refunded amount. */
    record Seed(OrderStatus status, long amountMinor, long refundedMinor) {}

    static final List<Seed> SEEDS = List.of(
            new Seed(OrderStatus.CREATED, 1_299, 0),
            new Seed(OrderStatus.PAID, 4_599, 0),
            new Seed(OrderStatus.PAID, 2_350, 0),
            new Seed(OrderStatus.PARTIALLY_REFUNDED, 18_900, 4_500),
            new Seed(OrderStatus.CREATED, 799, 0),
            new Seed(OrderStatus.PAYMENT_PENDING, 12_999, 0),
            new Seed(OrderStatus.PAID, 3_450, 0),
            new Seed(OrderStatus.CANCELLED, 999, 0),
            new Seed(OrderStatus.PAYMENT_FAILED, 6_400, 0),
            new Seed(OrderStatus.REFUNDED, 25_000, 25_000),
            new Seed(OrderStatus.PAID, 1_575, 0),
            new Seed(OrderStatus.CREATED, 8_999, 0),
            new Seed(OrderStatus.PAYMENT_PENDING, 4_200, 0),
            new Seed(OrderStatus.PARTIALLY_REFUNDED, 15_750, 2_000),
            new Seed(OrderStatus.PAID, 2_999, 0),
            new Seed(OrderStatus.CANCELLED, 560, 0),
            new Seed(OrderStatus.PAYMENT_FAILED, 7_325, 0),
            new Seed(OrderStatus.PAID, 11_000, 0),
            new Seed(OrderStatus.CREATED, 3_999, 0),
            new Seed(OrderStatus.REFUNDED, 2_150, 2_150),
            new Seed(OrderStatus.PAYMENT_PENDING, 9_450, 0),
            new Seed(OrderStatus.PAID, 1_899, 0),
            new Seed(OrderStatus.PARTIALLY_REFUNDED, 32_000, 8_000),
            new Seed(OrderStatus.PAYMENT_FAILED, 4_875, 0),
            new Seed(OrderStatus.CANCELLED, 1_250, 0));

    private final DemoDataStore store;
    private final OrderStore orders;
    private final AuditTrail audit;
    private final OrderIds ids;
    private final Clock clock;
    private final JsonMapper json;

    public DemoDataService(
            DemoDataStore store, OrderStore orders, AuditTrail audit, OrderIds ids, Clock clock, JsonMapper json) {
        this.store = store;
        this.orders = orders;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    /** Wipes the demo tables and seeds them again; records a DEMO_RESET audit row naming {@code actor}. */
    @Transactional
    public int reset(String actor) {
        store.truncateDemoTables();
        int seeded = seed();
        audit.append(new AuditRecord(
                null,
                null,
                "DEMO_RESET",
                json.writeValueAsString(Map.of("seededOrders", seeded)),
                actor,
                null,
                clock.instant()));
        return seeded;
    }

    /** Seeds only when there are no orders at all. Returns the number of orders seeded (0 or 25). */
    @Transactional
    public int seedIfEmpty() {
        return store.countOrders() == 0 ? seed() : 0;
    }

    private int seed() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (int i = 0; i < SEEDS.size(); i++) {
            Seed seed = SEEDS.get(i);
            // Spread over the last ~3 days, oldest first, so the list looks like real traffic.
            Instant created = now.minus(Duration.ofMinutes(173L * (SEEDS.size() - i)));
            long sequence = sequenceFor(seed.status());
            Instant updated = sequence == 0 && seed.status() == OrderStatus.CREATED
                    ? created
                    : created.plus(Duration.ofMinutes(4));
            Order order = orders.insert(new Order(
                    ids.next(),
                    MERCHANT_REF_PREFIX + (1001 + i),
                    seed.amountMinor(),
                    Money.USD,
                    seed.status(),
                    seed.refundedMinor(),
                    sequence,
                    0,
                    created,
                    updated,
                    sequence == 0 ? null : updated));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("status", order.status().name());
            detail.put("amountMinor", order.amountMinor());
            detail.put("synthetic", true);
            audit.append(new AuditRecord(
                    null, order.id(), "DEMO_SEEDED", json.writeValueAsString(detail), SEEDER_ACTOR, null, created));
        }
        return SEEDS.size();
    }

    /** The provider sequence an order in this status would have reached through MockPay. */
    static long sequenceFor(OrderStatus status) {
        return switch (status) {
            case CREATED, CANCELLED -> 0;
            case PAYMENT_PENDING -> 1;
            case PAID, PAYMENT_FAILED -> 2;
            case PARTIALLY_REFUNDED, REFUNDED -> 3;
        };
    }
}
