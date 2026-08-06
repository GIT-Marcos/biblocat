package com.biblocat.api.entity;

import jakarta.persistence.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "reconciliation")
public class Reconciliation {

    @Id
    private Integer id;

    @Column(nullable = false)
    private boolean pending;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Reconciliation() {
    }

    public Reconciliation(Integer id, boolean pending) {
        this.id = id;
        this.pending = pending;
    }

    public Integer getId() {
        return id;
    }

    public boolean isPending() {
        return pending;
    }

    public Long getVersion() {
        return version;
    }

    public void setPending(boolean pending) {
        this.pending = pending;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
