package com.javadrill.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Firestore collection: "payment_orders".
 * Document ID is the Razorpay order ID.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentOrder {
    private String orderId;
    private String uid;
    private int creditPack;
    private int amountPaise;
    private String currency;
    private String receipt;
    private String status;
    private String razorpayPaymentId;
    private long createdAt;
    private long paidAt;
}
