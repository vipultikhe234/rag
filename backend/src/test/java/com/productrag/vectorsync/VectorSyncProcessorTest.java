package com.productrag.vectorsync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.productrag.ai.AiProviderException;
import com.productrag.ai.AiProviderStatus;
import com.productrag.embedding.CatalogEntityType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VectorSyncProcessorTest {

    private static final String WORKER = "worker-1";
    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");

    private OutboxStore outboxStore;
    private ProductEmbeddingSynchronizer synchronizer;
    private CatalogEmbeddingSynchronizer catalogSynchronizer;
    private AiProviderStatus aiStatus;
    private VectorSyncProcessor processor;

    @BeforeEach
    void setUp() {
        outboxStore = mock(OutboxStore.class);
        synchronizer = mock(ProductEmbeddingSynchronizer.class);
        catalogSynchronizer = mock(CatalogEmbeddingSynchronizer.class);
        aiStatus = mock(AiProviderStatus.class);
        when(aiStatus.isConfigured()).thenReturn(true);
        BackoffPolicy backoff = new BackoffPolicy(Duration.ofSeconds(10), Duration.ofMinutes(15), () -> 0.5);
        processor = new VectorSyncProcessor(outboxStore, synchronizer, catalogSynchronizer,
                new SyncFailureClassifier(), backoff, aiStatus, 10, Duration.ofMinutes(5),
                Clock.fixed(NOW, ZoneOffset.UTC), WORKER);
    }

    @Test
    void successfulEventIsMarkedCompleted() {
        ClaimedEvent event = event(1, 3);
        when(outboxStore.claimBatch(WORKER, 10)).thenReturn(List.of(event));
        when(synchronizer.synchronize(event.aggregateId())).thenReturn(SyncResult.EMBEDDED);
        when(outboxStore.markCompleted(event.id(), WORKER, SyncResult.EMBEDDED)).thenReturn(true);

        assertThat(processor.processBatch()).isEqualTo(1);

        verify(outboxStore).markCompleted(event.id(), WORKER, SyncResult.EMBEDDED);
    }

    @Test
    void transientProviderFailureLeavesARetryableEventWithBackoff() {
        ClaimedEvent event = event(1, 3);
        when(outboxStore.claimBatch(WORKER, 10)).thenReturn(List.of(event));
        when(synchronizer.synchronize(event.aggregateId()))
                .thenThrow(AiProviderException.unavailable(new RuntimeException("timeout")));

        processor.processBatch();

        ArgumentCaptor<Instant> next = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(outboxStore).markRetryableFailure(eq(event.id()), eq(WORKER), error.capture(), next.capture());
        assertThat(next.getValue()).isEqualTo(NOW.plusSeconds(10));
        assertThat(error.getValue()).contains("temporarily unavailable");
        verify(outboxStore, never()).markPermanentFailure(any(), anyString(), anyString());
        verify(outboxStore, never()).markCompleted(any(), anyString(), any());
    }

    @Test
    void backoffGrowsExponentially() {
        ClaimedEvent third = event(3, 6);
        when(outboxStore.claimBatch(WORKER, 10)).thenReturn(List.of(third));
        when(synchronizer.synchronize(third.aggregateId()))
                .thenThrow(AiProviderException.unavailable(new RuntimeException("503")));

        processor.processBatch();

        verify(outboxStore).markRetryableFailure(eq(third.id()), eq(WORKER), anyString(), eq(NOW.plusSeconds(40)));
    }

    @Test
    void exhaustedAttemptsBecomePermanentFailure() {
        ClaimedEvent last = event(3, 3);
        when(outboxStore.claimBatch(WORKER, 10)).thenReturn(List.of(last));
        when(synchronizer.synchronize(last.aggregateId()))
                .thenThrow(AiProviderException.unavailable(new RuntimeException("503")));

        processor.processBatch();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(outboxStore).markPermanentFailure(eq(last.id()), eq(WORKER), error.capture());
        assertThat(error.getValue()).startsWith("Retry attempts exhausted");
    }

    @Test
    void permanentErrorsAreNotRetriedAutomatically() {
        ClaimedEvent event = event(1, 3);
        when(outboxStore.claimBatch(WORKER, 10)).thenReturn(List.of(event));
        when(synchronizer.synchronize(event.aggregateId()))
                .thenThrow(new PermanentSyncException("dimension mismatch"));

        processor.processBatch();

        verify(outboxStore).markPermanentFailure(eq(event.id()), eq(WORKER), anyString());
        verify(outboxStore, never()).markRetryableFailure(any(), anyString(), anyString(), any());
    }

    @Test
    void rejectedRequestsAreNotRetriedAutomatically() {
        ClaimedEvent event = event(1, 3);
        when(outboxStore.claimBatch(WORKER, 10)).thenReturn(List.of(event));
        when(synchronizer.synchronize(event.aggregateId()))
                .thenThrow(AiProviderException.rejected(new RuntimeException("401 invalid api key sk-abcdefghijk")));

        processor.processBatch();

        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(outboxStore).markPermanentFailure(eq(event.id()), eq(WORKER), error.capture());
        assertThat(error.getValue()).doesNotContain("sk-abcdefghijk").contains("sk-***");
    }

    @Test
    void oneFailingEventDoesNotBlockTheRestOfTheBatch() {
        ClaimedEvent failing = event(1, 3);
        ClaimedEvent ok = event(1, 3);
        when(outboxStore.claimBatch(WORKER, 10)).thenReturn(List.of(failing, ok));
        when(synchronizer.synchronize(failing.aggregateId())).thenThrow(new RuntimeException("boom"));
        when(synchronizer.synchronize(ok.aggregateId())).thenReturn(SyncResult.UNCHANGED);
        when(outboxStore.markCompleted(ok.id(), WORKER, SyncResult.UNCHANGED)).thenReturn(true);

        assertThat(processor.processBatch()).isEqualTo(2);

        verify(outboxStore).markRetryableFailure(eq(failing.id()), eq(WORKER), anyString(), any());
        verify(outboxStore).markCompleted(ok.id(), WORKER, SyncResult.UNCHANGED);
    }

    @Test
    void nothingIsClaimedWhileTheProviderIsNotConfigured() {
        when(aiStatus.isConfigured()).thenReturn(false);

        assertThat(processor.processBatch()).isZero();

        verify(outboxStore, never()).claimBatch(anyString(), anyInt());
    }

    @Test
    void drainStopsWhenTheQueueIsEmpty() {
        when(outboxStore.claimBatch(WORKER, 10)).thenReturn(List.of(event(1, 3)), List.of());
        when(synchronizer.synchronize(any())).thenReturn(SyncResult.EMBEDDED);

        assertThat(processor.drain()).isEqualTo(1);
    }

    @Test
    void staleRecoveryUsesConfiguredTimeout() {
        when(outboxStore.recoverStale(Duration.ofMinutes(5))).thenReturn(2);

        assertThat(processor.recoverStale()).isEqualTo(2);
    }

    @Test
    void catalogEntityEventsGoToTheCatalogSynchronizer() {
        ClaimedEvent event = new ClaimedEvent(UUID.randomUUID(), "BRAND", UUID.randomUUID(),
                VectorSyncEventType.ENTITY_CHANGED, 1, 3);
        when(outboxStore.claimBatch(WORKER, 10)).thenReturn(List.of(event));
        when(catalogSynchronizer.synchronize(CatalogEntityType.BRAND, event.aggregateId()))
                .thenReturn(SyncResult.EMBEDDED);
        when(outboxStore.markCompleted(event.id(), WORKER, SyncResult.EMBEDDED)).thenReturn(true);

        processor.processBatch();

        verify(outboxStore).markCompleted(event.id(), WORKER, SyncResult.EMBEDDED);
        verify(synchronizer, never()).synchronize(any());
    }

    @Test
    void anUnknownAggregateTypeFailsPermanently() {
        ClaimedEvent event = new ClaimedEvent(UUID.randomUUID(), "WAREHOUSE", UUID.randomUUID(),
                VectorSyncEventType.ENTITY_CHANGED, 1, 3);
        when(outboxStore.claimBatch(WORKER, 10)).thenReturn(List.of(event));

        processor.processBatch();

        verify(outboxStore).markPermanentFailure(eq(event.id()), eq(WORKER), anyString());
    }

    private static ClaimedEvent event(int attempts, int maxAttempts) {
        return new ClaimedEvent(UUID.randomUUID(), "PRODUCT", UUID.randomUUID(), VectorSyncEventType.PRODUCT_UPDATED,
                attempts, maxAttempts);
    }
}
