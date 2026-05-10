package com.assessarc.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RedeemRequest {
    private String id;
    private String uid;
    private String userEmail;
    private String upiId;
    private int amount;
    private String status; // PENDING | APPROVED | REJECTED | DONE
    private String payoutId;
    private String adminNote;
    private long createdAt;
    private long updatedAt;
    private long approvedAt;
    private long doneAt;
    private long rejectedAt;
}
