package com.mockapilab.modules.runtime.generation;

import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Encapsulates deterministic pseudo-random state for reproducible mock data generation.
 */
public class GenerationSeed {

    private final long initialSeed;
    private final Random random;

    public GenerationSeed(long seed) {
        this.initialSeed = seed;
        this.random = new Random(seed);
    }

    public static GenerationSeed from(Long optionalSeed) {
        long seed = (optionalSeed != null) ? optionalSeed : System.currentTimeMillis();
        return new GenerationSeed(seed);
    }

    public static GenerationSeed from(String textKey) {
        long seed = (textKey != null) ? textKey.hashCode() : 42L;
        return new GenerationSeed(seed);
    }

    public long getInitialSeed() {
        return initialSeed;
    }

    public int nextInt(int bound) {
        if (bound <= 0) return 0;
        return random.nextInt(bound);
    }

    public int nextInt(int min, int max) {
        if (max <= min) return min;
        return min + random.nextInt(max - min + 1);
    }

    public double nextDouble() {
        return random.nextDouble();
    }

    public double nextDouble(double min, double max) {
        if (max <= min) return min;
        return min + (random.nextDouble() * (max - min));
    }

    public boolean nextBoolean() {
        return random.nextBoolean();
    }

    public <T> T choose(List<T> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(nextInt(list.size()));
    }

    public UUID nextUuid() {
        long mostSigBits = random.nextLong();
        long leastSigBits = random.nextLong();
        return new UUID(mostSigBits, leastSigBits);
    }

    /**
     * Derives a deterministic child seed for isolated sub-context generation.
     */
    public GenerationSeed branch(String contextKey) {
        long derivedSeed = (initialSeed * 31L) + (contextKey != null ? contextKey.hashCode() : 0);
        return new GenerationSeed(derivedSeed);
    }
}
