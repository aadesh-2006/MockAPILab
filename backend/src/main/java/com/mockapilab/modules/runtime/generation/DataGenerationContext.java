package com.mockapilab.modules.runtime.generation;

import com.mockapilab.modules.contract.model.normalized.NormalizedContract;
import com.mockapilab.modules.contract.model.normalized.NormalizedSchema;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Context passed through value generators during recursive schema evaluation.
 */
public class DataGenerationContext {

    private static final int DEFAULT_MAX_DEPTH = 5;
    private static final int DEFAULT_ARRAY_SIZE = 2;

    private final GenerationSeed seed;
    private final NormalizedContract contract;
    private final int depth;
    private final int maxDepth;
    private final int arraySize;
    private final String propertyName;
    private final String currentPath;
    private final Set<String> activeRefs;

    public DataGenerationContext(
            GenerationSeed seed,
            NormalizedContract contract,
            int depth,
            int maxDepth,
            int arraySize,
            String propertyName,
            String currentPath,
            Set<String> activeRefs
    ) {
        this.seed = seed != null ? seed : GenerationSeed.from(42L);
        this.contract = contract;
        this.depth = depth;
        this.maxDepth = maxDepth > 0 ? maxDepth : DEFAULT_MAX_DEPTH;
        this.arraySize = arraySize > 0 ? arraySize : DEFAULT_ARRAY_SIZE;
        this.propertyName = propertyName;
        this.currentPath = currentPath != null ? currentPath : "";
        this.activeRefs = activeRefs != null ? Collections.unmodifiableSet(new HashSet<>(activeRefs)) : Collections.emptySet();
    }

    public static DataGenerationContext root(GenerationSeed seed, NormalizedContract contract) {
        return new DataGenerationContext(seed, contract, 0, DEFAULT_MAX_DEPTH, DEFAULT_ARRAY_SIZE, null, "", Collections.emptySet());
    }

    public static DataGenerationContext root(GenerationSeed seed, NormalizedContract contract, int arraySize) {
        return new DataGenerationContext(seed, contract, 0, DEFAULT_MAX_DEPTH, arraySize, null, "", Collections.emptySet());
    }

    public DataGenerationContext forProperty(String propName, NormalizedSchema schema) {
        String nextPath = currentPath.isEmpty() ? propName : currentPath + "." + propName;
        GenerationSeed childSeed = seed.branch(nextPath);
        return new DataGenerationContext(
                childSeed,
                contract,
                depth + 1,
                maxDepth,
                arraySize,
                propName,
                nextPath,
                activeRefs
        );
    }

    public DataGenerationContext forArrayItem(int index) {
        String nextPath = currentPath + "[" + index + "]";
        GenerationSeed childSeed = seed.branch(nextPath);
        return new DataGenerationContext(
                childSeed,
                contract,
                depth + 1,
                maxDepth,
                arraySize,
                propertyName != null ? propertyName + "Item" : "item",
                nextPath,
                activeRefs
        );
    }

    public DataGenerationContext withRef(String refName) {
        Set<String> updatedRefs = new HashSet<>(activeRefs);
        updatedRefs.add(refName);
        return new DataGenerationContext(
                seed,
                contract,
                depth + 1,
                maxDepth,
                arraySize,
                propertyName,
                currentPath,
                updatedRefs
        );
    }

    public boolean isDepthExceeded() {
        return depth >= maxDepth;
    }

    public boolean isRefActive(String refName) {
        return activeRefs.contains(refName);
    }

    public GenerationSeed getSeed() {
        return seed;
    }

    public NormalizedContract getContract() {
        return contract;
    }

    public int getDepth() {
        return depth;
    }

    public int getArraySize() {
        return arraySize;
    }

    public String getPropertyName() {
        return propertyName;
    }

    public String getCurrentPath() {
        return currentPath;
    }
}
