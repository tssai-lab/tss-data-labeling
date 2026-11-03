package com.tss.platform.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@Entity
@Table(name = "scheduler_lock")
public class SchedulerLock {

    @Id
    @Column(name = "name", length = 128)
    private String name;

    @Column(name = "owner_id", nullable = false, length = 128)
    private String ownerId;

    @Column(name = "locked_until", nullable = false)
    private Instant lockedUntil;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
