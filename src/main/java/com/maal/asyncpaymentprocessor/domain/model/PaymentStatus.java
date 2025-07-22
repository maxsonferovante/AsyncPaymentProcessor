package com.maal.asyncpaymentprocessor.domain.model;

public enum PaymentStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    RETRY
}