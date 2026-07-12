package com.biblocat.dto;

import java.util.List;

public record ReconcileRequest(
        List<Operation> operations
) {}
