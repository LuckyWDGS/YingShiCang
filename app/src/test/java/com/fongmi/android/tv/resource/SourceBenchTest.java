package com.fongmi.android.tv.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.function.Function;

public class SourceBenchTest {

    private long now = 1_000_000;
    private String saved = "";

    private SourceBench bench() {
        return new SourceBench(() -> now, saved, value -> saved = value, message -> {});
    }

    private static void fail(SourceBench bench, String id, int rounds) {
        for (int i = 0; i < rounds; i++) bench.failed(id);
    }

    @Test
    public void aSourceRestsAfterThreeFailedRounds() {
        SourceBench bench = bench();
        fail(bench, "a", 2);
        assertFalse(bench.resting("a"));
        bench.failed("a");
        assertTrue(bench.resting("a"));
    }

    @Test
    public void anAnswerClearsTheCount() {
        SourceBench bench = bench();
        fail(bench, "a", 2);
        bench.answered("a");
        fail(bench, "a", 2);
        assertFalse(bench.resting("a"));
    }

    @Test
    public void afterItsRestOneMoreFailureRestsItAgain() {
        SourceBench bench = bench();
        fail(bench, "a", 3);
        now += SourceBench.REST_MS + 1;
        assertFalse(bench.resting("a"));
        bench.failed("a");
        assertTrue(bench.resting("a"));
    }

    @Test
    public void restingSourcesAreLeftOutUnlessAllAreResting() {
        SourceBench bench = bench();
        fail(bench, "a", 3);
        assertEquals(List.of("b"), bench.active(List.of("a", "b"), Function.identity()));
        fail(bench, "b", 3);
        assertEquals(List.of("a", "b"), bench.active(List.of("a", "b"), Function.identity()));
    }

    @Test
    public void aClockMovedBackDoesNotRestASourceForever() {
        SourceBench bench = bench();
        fail(bench, "a", 3);
        now -= SourceBench.REST_MS * 100;
        assertFalse(bench.resting("a"));
    }

    @Test
    public void restsSurviveARestart() {
        SourceBench bench = bench();
        fail(bench, "a", 3);
        fail(bench, "b", 2);
        SourceBench next = bench();
        assertTrue(next.resting("a"));
        next.failed("b");
        assertTrue(next.resting("b"));
    }

    @Test
    public void aDamagedSaveStartsClean() {
        saved = "{not json";
        assertFalse(bench().resting("a"));
    }
}
