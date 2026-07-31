package com.jmammadov.seatres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.jmammadov.seatres.domain.HoldView;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The sweeper's one job: return lapsed holds' seats to the pool (for UI freshness) without ever
 * touching anything still live. Correctness without the sweeper is proven elsewhere
 * (ExpiryConfirmRaceIT); this proves the janitor itself behaves.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "seatres.sweeper.enabled=true",
      "seatres.sweeper.interval=100ms",
      "seatres.hold-ttl=60s"
    })
class SweeperIT extends AbstractApiIT {

  @Test
  void sweeperReleasesLapsedHoldsAndLeavesLiveOnesAlone() {
    List<UUID> lapsing = seatsOfRow("G").subList(0, 2);
    List<UUID> live = seatsOfRow("G").subList(2, 4);

    HoldView doomed = holdSeatsOk("dave", lapsing);
    HoldView healthy = holdSeatsOk("helen", live);
    shiftHoldExpiry(doomed.id(), -1);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> {
              String holdStatus =
                  jdbc.sql("SELECT status FROM hold WHERE id = :id")
                      .param("id", doomed.id())
                      .query(String.class)
                      .single();
              assertThat(holdStatus).isEqualTo("EXPIRED");
              List<String> seatStatuses =
                  jdbc.sql("SELECT status FROM seat WHERE id IN (:ids)")
                      .param("ids", lapsing)
                      .query(String.class)
                      .list();
              assertThat(seatStatuses).containsOnly("AVAILABLE");
            });

    // The healthy hold was not touched.
    HoldView healthyAfter = parse(get("/api/holds/" + healthy.id()), HoldView.class);
    assertThat(healthyAfter.status().name()).isEqualTo("ACTIVE");
    assertThat(confirm(healthy.id()).status()).isEqualTo(201);
  }
}
