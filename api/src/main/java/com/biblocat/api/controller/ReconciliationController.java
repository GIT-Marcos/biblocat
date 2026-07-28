package com.biblocat.api.controller;

import com.biblocat.api.dto.response.ReconciliationAckResponse;
import com.biblocat.api.dto.response.ReconciliationPendingResponse;
import com.biblocat.api.dto.response.ReconciliationStatusResponse;
import com.biblocat.api.service.ReconciliationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reconcile")
public class ReconciliationController {

    private final ReconciliationService reconciliationService;

    public ReconciliationController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @PostMapping
    public ResponseEntity<ReconciliationStatusResponse> request() {
        return ResponseEntity.ok(reconciliationService.request());
    }

    @GetMapping("/pending")
    public ResponseEntity<ReconciliationPendingResponse> pending() {
        return ResponseEntity.ok(reconciliationService.isPending());
    }

    @PostMapping("/ack")
    public ResponseEntity<ReconciliationAckResponse> ack() {
        return ResponseEntity.ok(reconciliationService.ack());
    }
}
