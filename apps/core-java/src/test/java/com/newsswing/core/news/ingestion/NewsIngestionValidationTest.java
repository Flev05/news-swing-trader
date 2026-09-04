package com.newsswing.core.news.ingestion;

import static com.newsswing.core.news.domain.NewsEvents.Content;
import static com.newsswing.core.news.domain.NewsEvents.ProviderRevisionKey;
import static com.newsswing.core.news.domain.NewsEvents.UnhashedNewsEvent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.newsswing.core.contracts.SharedContractValidator.Violation;
import com.newsswing.core.news.domain.NewsCanonicalizer;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class NewsIngestionValidationTest {

  private static final Instant NOW = Instant.parse("2026-01-15T14:30:30Z");

  @Test
  void mapsCanonicalizationFailuresToInvalidEventBeforeDatabaseWork() {
    var clock = new CountingClock(NOW);
    var repository = mock(NewsIngestionRepository.class);
    var transactionManager = mock(PlatformTransactionManager.class);
    var coordinator =
        coordinator(
            new NewsCanonicalizer(),
            mock(NewsEventContractValidator.class),
            repository,
            transactionManager,
            clock);

    assertThatThrownBy(() -> coordinator.ingest(event(null)))
        .isInstanceOf(NewsIngestionException.class)
        .extracting(exception -> ((NewsIngestionException) exception).code())
        .isEqualTo(NewsIngestionException.Code.INVALID_EVENT);

    assertThat(clock.calls()).isEqualTo(1);
    verifyNoInteractions(repository, transactionManager);
  }

  @Test
  void mapsContractFailuresToInvalidEventBeforeDatabaseWork() {
    var clock = new CountingClock(NOW);
    var canonicalizer = new NewsCanonicalizer();
    var validator = mock(NewsEventContractValidator.class);
    var repository = mock(NewsIngestionRepository.class);
    var transactionManager = mock(PlatformTransactionManager.class);
    var input = event("Valid headline");
    var canonical = canonicalizer.canonicalize(input);
    when(validator.validate(canonical.payloadJson()))
        .thenReturn(List.of(new Violation("required", "", "headline is required")));
    var coordinator = coordinator(canonicalizer, validator, repository, transactionManager, clock);

    assertThatThrownBy(() -> coordinator.ingest(input))
        .isInstanceOf(NewsIngestionException.class)
        .satisfies(
            exception -> {
              var rejection = (NewsIngestionException) exception;
              assertThat(rejection.code()).isEqualTo(NewsIngestionException.Code.INVALID_EVENT);
              assertThat(rejection.violations()).hasSize(1);
            });

    assertThat(clock.calls()).isEqualTo(1);
    verifyNoInteractions(repository, transactionManager);
  }

  private NewsIngestionCoordinator coordinator(
      NewsCanonicalizer canonicalizer,
      NewsEventContractValidator validator,
      NewsIngestionRepository repository,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    return new NewsIngestionCoordinator(
        canonicalizer,
        validator,
        repository,
        clock,
        new NewsIngestionProperties(1),
        transactionManager);
  }

  private UnhashedNewsEvent event(String headline) {
    return new UnhashedNewsEvent(
        "1.0.0",
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        new ProviderRevisionKey("fixture_wire", "validation-news", "revision-1"),
        "https://news.example.test/validation-news",
        NOW.minusSeconds(30),
        NOW.minusSeconds(30),
        NOW.minusSeconds(10),
        NOW.plusSeconds(60),
        headline,
        new Content("en", "A durable summary.", null),
        List.of("ACME"));
  }

  private static final class CountingClock extends Clock {

    private final Instant instant;
    private final AtomicInteger calls = new AtomicInteger();

    private CountingClock(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      calls.incrementAndGet();
      return instant;
    }

    int calls() {
      return calls.get();
    }
  }
}
