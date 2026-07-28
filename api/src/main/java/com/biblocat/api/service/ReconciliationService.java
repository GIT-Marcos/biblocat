package com.biblocat.api.service;

import com.biblocat.api.dto.response.ReconciliationAckResponse;
import com.biblocat.api.dto.response.ReconciliationPendingResponse;
import com.biblocat.api.dto.response.ReconciliationStatusResponse;
import com.biblocat.api.entity.Reconciliation;
import com.biblocat.api.repository.ReconciliationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ReconciliationService {

    private final ReconciliationRepository reconciliationRepository;

    public ReconciliationService(ReconciliationRepository reconciliationRepository) {
        this.reconciliationRepository = reconciliationRepository;
    }

    public ReconciliationStatusResponse request() {
        Reconciliation rec = reconciliationRepository.findById(1)
                .orElseGet(() -> new Reconciliation(1, false));
        rec.setPending(true);
        reconciliationRepository.save(rec);
        return new ReconciliationStatusResponse(true, "Reconciliation pending.");
    }

    @Transactional(readOnly = true)
    public ReconciliationPendingResponse isPending() {
        Reconciliation rec = reconciliationRepository.findById(1)
                .orElseGet(() -> new Reconciliation(1, false));
        return new ReconciliationPendingResponse(rec.isPending());
    }

    public ReconciliationAckResponse ack() {
        Reconciliation rec = reconciliationRepository.findById(1)
                .orElseGet(() -> new Reconciliation(1, false));
        rec.setPending(false);
        reconciliationRepository.save(rec);
        return new ReconciliationAckResponse(true);
    }
}
