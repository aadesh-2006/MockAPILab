package com.mockapilab.modules.contract.model.normalized;

public record ContractMetadata(
        String title,
        String description,
        String version,
        String formatVersion
) {
    public static ContractMetadata of(String title, String description, String version) {
        return new ContractMetadata(title, description, version, "1.0.0");
    }
}
