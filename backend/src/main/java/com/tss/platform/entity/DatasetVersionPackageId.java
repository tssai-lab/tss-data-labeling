package com.tss.platform.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

@Getter
@Setter
@NoArgsConstructor
public class DatasetVersionPackageId implements Serializable {

    private String datasetVersionId;
    private String packageId;

    public DatasetVersionPackageId(String datasetVersionId, String packageId) {
        this.datasetVersionId = datasetVersionId;
        this.packageId = packageId;
    }

    @Override
    public boolean equals(Object value) {
        if (this == value) {
            return true;
        }
        if (!(value instanceof DatasetVersionPackageId other)) {
            return false;
        }
        return Objects.equals(datasetVersionId, other.datasetVersionId)
                && Objects.equals(packageId, other.packageId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(datasetVersionId, packageId);
    }
}
