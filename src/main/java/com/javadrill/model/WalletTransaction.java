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
    private String type;           // RECHARGE | BONUS | INTERVIEW_DEBIT | REDEEM_REQUEST | REDEEM_REFUND | REFERRAL_BONUS | FIRST_LOGIN_BONUS
    private int amount;
    private int balanceBefore;
    private int balanceAfter;
    private int purchasedBefore;
    private int purchasedAfter;
    private int bonusBefore;
    private int bonusAfter;
    private int purchasedDelta;
    private int bonusDelta;
    private String description;
    private String razorpayOrderId;
    private String razorpayPaymentId;
    private String interviewId;    // for debits
    private String redeemRequestId;
    private long createdAt;
}
