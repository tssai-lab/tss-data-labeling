package com.tss.platform.dto.v2;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;

/**
 * Presence-aware patch body. A called setter means that the property was
 * supplied, even when its JSON value is null.
 */
public final class V2CodeAssetPatchRequest {

    private Long assetRevision;
    private String name;
    private String trainingProfile;
    private String purpose;
    private String runtime;
    private String entryScript;
    private String trainingType;
    private String remark;

    private boolean namePresent;
    private boolean trainingProfilePresent;
    private boolean purposePresent;
    private boolean runtimePresent;
    private boolean entryScriptPresent;
    private boolean trainingTypePresent;
    private boolean remarkPresent;

    public Long getAssetRevision() {
        return assetRevision;
    }

    @JsonSetter("assetRevision")
    public void setAssetRevision(Long assetRevision) {
        this.assetRevision = assetRevision;
    }

    public String getName() {
        return name;
    }

    @JsonSetter("name")
    public void setName(String name) {
        this.namePresent = true;
        this.name = name;
    }

    public String getTrainingProfile() {
        return trainingProfile;
    }

    @JsonSetter("trainingProfile")
    public void setTrainingProfile(String trainingProfile) {
        this.trainingProfilePresent = true;
        this.trainingProfile = trainingProfile;
    }

    public String getPurpose() {
        return purpose;
    }

    @JsonSetter("purpose")
    public void setPurpose(String purpose) {
        this.purposePresent = true;
        this.purpose = purpose;
    }

    public String getRuntime() {
        return runtime;
    }

    @JsonSetter("runtime")
    public void setRuntime(String runtime) {
        this.runtimePresent = true;
        this.runtime = runtime;
    }

    public String getEntryScript() {
        return entryScript;
    }

    @JsonSetter("entryScript")
    public void setEntryScript(String entryScript) {
        this.entryScriptPresent = true;
        this.entryScript = entryScript;
    }

    public String getTrainingType() {
        return trainingType;
    }

    @JsonSetter("trainingType")
    public void setTrainingType(String trainingType) {
        this.trainingTypePresent = true;
        this.trainingType = trainingType;
    }

    public String getRemark() {
        return remark;
    }

    @JsonSetter("remark")
    public void setRemark(String remark) {
        this.remarkPresent = true;
        this.remark = remark;
    }

    @JsonIgnore
    public boolean isNamePresent() {
        return namePresent;
    }

    @JsonIgnore
    public boolean isTrainingProfilePresent() {
        return trainingProfilePresent;
    }

    @JsonIgnore
    public boolean isPurposePresent() {
        return purposePresent;
    }

    @JsonIgnore
    public boolean isRuntimePresent() {
        return runtimePresent;
    }

    @JsonIgnore
    public boolean isEntryScriptPresent() {
        return entryScriptPresent;
    }

    @JsonIgnore
    public boolean isTrainingTypePresent() {
        return trainingTypePresent;
    }

    @JsonIgnore
    public boolean isRemarkPresent() {
        return remarkPresent;
    }

    @JsonIgnore
    public boolean hasChanges() {
        return namePresent
                || trainingProfilePresent
                || purposePresent
                || runtimePresent
                || entryScriptPresent
                || trainingTypePresent
                || remarkPresent;
    }
}
