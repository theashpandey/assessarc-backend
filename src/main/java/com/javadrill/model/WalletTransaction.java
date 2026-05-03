package com.javadrill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Firestore collection: "wallet_transactions"
 * Records every credit/debit for audit trail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletTransaction {
    private String id;
    private String uid;
    private String type;           // "credit" | "debit"
    private int amount;
    private int balanceBefore;
    private int balanceAfter;
    private String description;
    private String razorpayOrderId;
    private String razorpayPaymentId;
    private String interviewId;    // for debits
    private long createdAt;
}
