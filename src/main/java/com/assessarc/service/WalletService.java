package com.assessarc.service;

import com.google.cloud.firestore.Firestore;
import com.assessarc.config.AppProperties;
import com.assessarc.dto.Dto;
import com.assessarc.model.PaymentOrder;
import com.assessarc.model.RedeemRequest;
import com.assessarc.model.User;
import com.assessarc.model.WalletTransaction;
import com.assessarc.repository.UserRepository;
import com.assessarc.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private static final String PAYMENT_ORDERS_COLLECTION = "payment_orders";
    private static final String USERS_COLLECTION = "users";
    private static final String WALLET_TRANSACTIONS_COLLECTION = "wallet_transactions";
    private static final String REDEEM_REQUESTS_COLLECTION = "redeem_requests";
    private static final Pattern UPI_PATTERN = Pattern.compile("^[A-Za-z0-9._-]{2,}@[A-Za-z]{2,}[A-Za-z0-9.-]*$");

    private static final double PAYOUT_FEE = 2.5; // Rounded up from 2.5
    private static final double GST_RATE = 0.18;
    private static final int TOTAL_FEE = (int) Math.ceil(PAYOUT_FEE * (1 + GST_RATE)); // 3 * 1.18 = 3.54 → 4

    private final UserRepository userRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final AppProperties props;
    private final WebClient.Builder webClientBuilder;
    private final Firestore firestore;

    private record Plan(String name, int totalCredits, int purchasedCredits, int bonusCredits, int amountPaise) {}
    private record Balances(int purchased, int bonus) {
        int total() { return purchased + bonus; }
    }

    private static final List<Plan> PLANS = List.of(
            new Plan("Single", 10, 10, 0, 1000),
            new Plan("Starter", 35, 30, 5, 2900),
            new Plan("Pro", 70, 60, 10, 5900),
            new Plan("Elite", 115, 100, 15, 9900),
            new Plan("Titan", 220, 200, 20, 19900)
    );

    public Dto.WalletBalanceResponse getBalance(String uid) {
        User user = userRepository.findById(uid)
                .orElseThrow(() -> new RuntimeException("User not found"));
        Balances balances = balances(user);
        return Dto.WalletBalanceResponse.builder()
                .credits(balances.total())
                .purchasedCredits(balances.purchased())
                .bonusCredits(balances.bonus())
                .totalCredits(balances.total())
                .redeemableBalance(balances.purchased())
                .upiId(user.getUpiId())
                .build();
    }

    public List<Dto.WalletTransactionItem> getHistory(String uid) {
        return walletTransactionRepository.findByUid(uid, 50).stream()
                .map(this::toTransactionItem)
                .collect(Collectors.toList());
    }

    public Dto.WalletBalanceResponse saveUpi(String uid, Dto.SaveUpiRequest req) {
        String upi = req != null ? normalizeUpi(req.getUpiId()) : "";
        validateUpi(upi);
        try {
            firestore.collection(USERS_COLLECTION).document(uid).update("upiId", upi).get();
            return getBalance(uid);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to save UPI ID", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to save UPI ID", e);
        }
    }

    public Dto.CreateOrderResponse createOrder(String uid, int creditPack) {
        if (uid == null || uid.isBlank()) throw new RuntimeException("Unauthorized");
        Plan plan = planFor(creditPack);

        String keyId = props.getRazorpay().getKeyId();
        String keySecret = props.getRazorpay().getKeySecret();
        if (isBlank(keyId) || isBlank(keySecret)) throw new RuntimeException("Payment gateway is not configured");

        String receipt = "jd_" + uid.substring(0, Math.min(8, uid.length())) + "_"
                + plan.totalCredits() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        Map<String, Object> order;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> createdOrder = webClientBuilder.build()
                    .post()
                    .uri("https://api.razorpay.com/v1/orders")
                    .headers(headers -> headers.setBasicAuth(keyId, keySecret))
                    .bodyValue(Map.of("amount", plan.amountPaise(), "currency", "INR", "receipt", receipt))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            order = createdOrder;
        } catch (Exception e) {
            log.error("Razorpay order creation failed for user {} plan {}: {}", uid, plan.name(), e.getMessage());
            throw new RuntimeException("Payment order creation failed. Please try again.");
        }

        String orderId = order != null ? String.valueOf(order.get("id")) : null;
        if (isBlank(orderId) || "null".equals(orderId)) throw new RuntimeException("Payment order creation failed");

        savePaymentOrder(PaymentOrder.builder()
                .orderId(orderId)
                .uid(uid)
                .creditPack(plan.totalCredits())
                .purchasedCredits(plan.purchasedCredits())
                .bonusCredits(plan.bonusCredits())
                .amountPaise(plan.amountPaise())
                .currency("INR")
                .receipt(receipt)
                .status("CREATED")
                .createdAt(System.currentTimeMillis())
                .build());

        return Dto.CreateOrderResponse.builder()
                .orderId(orderId)
                .amount(plan.amountPaise())
                .currency("INR")
                .keyId(keyId)
                .build();
    }

    public Dto.VerifyPaymentResponse verifyPayment(String uid, Dto.VerifyPaymentRequest req) {
        if (uid == null || uid.isBlank()) throw new RuntimeException("Unauthorized");
        if (req == null || isBlank(req.getRazorpayOrderId()) || isBlank(req.getRazorpayPaymentId()) || isBlank(req.getRazorpaySignature())) {
            throw new RuntimeException("Payment verification details are required");
        }
        if (!verifySignature(req.getRazorpayOrderId(), req.getRazorpayPaymentId(), req.getRazorpaySignature())) {
            return Dto.VerifyPaymentResponse.builder()
                    .success(false)
                    .message("Payment verification failed. Please contact support.")
                    .build();
        }
        return creditWalletForPayment(uid, req);
    }

    public Dto.RedeemResponse createRedeemRequest(String uid, Dto.RedeemRequestDto req) {
        int amount = req != null ? req.getAmount() : 0;
        if (amount <= 0) throw new RuntimeException("Redeem amount must be greater than 0");
        String upi = normalizeUpi(req.getUpiId());
        if (isBlank(upi)) {
            User user = userRepository.findById(uid).orElseThrow(() -> new RuntimeException("User not found"));
            upi = normalizeUpi(user.getUpiId());
        }
        validateUpi(upi);

        String requestId = UUID.randomUUID().toString();
        String txId = "redeem_" + requestId;
        long now = System.currentTimeMillis();
        var userRef = firestore.collection(USERS_COLLECTION).document(uid);
        var reqRef = firestore.collection(REDEEM_REQUESTS_COLLECTION).document(requestId);
        var txRef = firestore.collection(WALLET_TRANSACTIONS_COLLECTION).document(txId);

        try {
            String finalUpi = upi;
            return firestore.runTransaction(transaction -> {
                var userSnap = transaction.get(userRef).get();
                if (!userSnap.exists()) throw new RuntimeException("User not found");
                User user = userSnap.toObject(User.class);
                Balances before = balances(user);
                if (before.purchased() < amount + TOTAL_FEE) {
                    throw new RuntimeException("Insufficient purchased credits. Available: " + before.purchased() + ", required: " + (amount + TOTAL_FEE)+" with processing fee.");
                }
                String activeRedeemId = userSnap.getString("activeRedeemRequestId");
                String activeRedeemStatus = userSnap.getString("activeRedeemStatus");
                if (!isBlank(activeRedeemId) && ("PENDING".equals(activeRedeemStatus) || "APPROVED".equals(activeRedeemStatus))) {
                    throw new RuntimeException("You already have a pending redeem request.");
                }
                Balances after = new Balances(before.purchased() - (amount + TOTAL_FEE), 0);

                RedeemRequest redeem = RedeemRequest.builder()
                        .id(requestId)
                        .uid(uid)
                        .userEmail(user != null ? user.getEmail() : null)
                        .upiId(finalUpi)
                        .amount(amount)
                        .status("PENDING")
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
                WalletTransaction walletTx = txBuilder(txId, uid, "REDEEM_REQUEST", amount, before, after,
                        "Redeem request created for ₹" + amount + ". Fee ₹" + TOTAL_FEE + " deducted. Bonus balance reset.", now)
                        .redeemRequestId(requestId)
                        .purchasedDelta(-(amount + TOTAL_FEE))
                        .bonusDelta(-before.bonus())
                        .build();

                transaction.update(userRef,
                        "purchasedCredits", after.purchased(),
                        "bonusCredits", after.bonus(),
                        "walletCredits", after.total(),
                        "upiId", finalUpi,
                        "activeRedeemRequestId", requestId,
                        "activeRedeemStatus", "PENDING");
                transaction.set(reqRef, redeem);
                transaction.set(txRef, walletTx);
                return Dto.RedeemResponse.builder()
                        .success(true)
                        .requestId(requestId)
                        .status("PENDING")
                        .purchasedCredits(after.purchased())
                        .bonusCredits(after.bonus())
                        .totalCredits(after.total())
                        .message("Redeem request submitted.")
                        .build();
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Redeem request failed", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw new RuntimeException("Redeem request failed", e);
        }
    }

    public List<Dto.RedeemRequestItem> listRedeemRequests() {
        try {
            return firestore.collection(REDEEM_REQUESTS_COLLECTION).get().get().getDocuments().stream()
                    .map(doc -> {
                        RedeemRequest req = doc.toObject(RedeemRequest.class);
                        if (req != null) req.setId(doc.getId());
                        return req;
                    })
                    .filter(req -> req != null)
                    .sorted(Comparator.comparingLong(RedeemRequest::getCreatedAt).reversed())
                    .map(this::toRedeemItem)
                    .collect(Collectors.toList());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Failed to load redeem requests", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Failed to load redeem requests", e);
        }
    }

    public Dto.RedeemRequestItem approveRedeem(String requestId, Dto.AdminRedeemActionRequest req) {
        RedeemRequest updated = updateRedeemStatus(requestId, "PENDING", "APPROVED", req, false);
        String payoutId = triggerPayoutBestEffort(updated);
        if (payoutId != null) {
            // success, set to DONE
            updated.setPayoutId(payoutId);
            updated.setStatus("DONE");
            updated.setDoneAt(System.currentTimeMillis());
            firestore.collection(REDEEM_REQUESTS_COLLECTION).document(requestId).set(updated);
            firestore.collection(USERS_COLLECTION).document(updated.getUid()).update("activeRedeemRequestId", null, "activeRedeemStatus", null);
        } else {
            // failed, reject
            return rejectRedeem(requestId, req);
        }
        return toRedeemItem(updated);
    }

    public Dto.RedeemRequestItem markRedeemDone(String requestId, Dto.AdminRedeemActionRequest req) {
        return toRedeemItem(updateRedeemStatus(requestId, "APPROVED", "DONE", req, false));
    }

    public Dto.RedeemRequestItem rejectRedeem(String requestId, Dto.AdminRedeemActionRequest req) {
        return toRedeemItem(updateRedeemStatus(requestId, null, "REJECTED", req, true));
    }

    private Dto.VerifyPaymentResponse creditWalletForPayment(String uid, Dto.VerifyPaymentRequest req) {
        try {
            String paymentTxId = paymentTransactionId(req.getRazorpayPaymentId());
            long now = System.currentTimeMillis();
            var orderRef = firestore.collection(PAYMENT_ORDERS_COLLECTION).document(req.getRazorpayOrderId());
            var userRef = firestore.collection(USERS_COLLECTION).document(uid);
            var txRef = firestore.collection(WALLET_TRANSACTIONS_COLLECTION).document(paymentTxId);

            return firestore.runTransaction(transaction -> {
                var txSnap = transaction.get(txRef).get();
                if (txSnap.exists()) {
                    WalletTransaction existing = txSnap.toObject(WalletTransaction.class);
                    if (existing != null && uid.equals(existing.getUid()) && req.getRazorpayOrderId().equals(existing.getRazorpayOrderId())) {
                        return Dto.VerifyPaymentResponse.builder()
                                .success(true)
                                .newBalance(existing.getBalanceAfter())
                                .purchasedCredits(existing.getPurchasedAfter())
                                .bonusCredits(existing.getBonusAfter())
                                .message(existing.getAmount() + " credits already added.")
                                .build();
                    }
                    throw new RuntimeException("Payment has already been used");
                }

                var orderSnap = transaction.get(orderRef).get();
                if (!orderSnap.exists()) throw new RuntimeException("Payment order not found");
                PaymentOrder order = orderSnap.toObject(PaymentOrder.class);
                if (order == null) throw new RuntimeException("Invalid payment order");
                if (!uid.equals(order.getUid())) throw new RuntimeException("Payment order does not belong to this user");
                if (!"CREATED".equals(order.getStatus())) throw new RuntimeException("Payment order is already processed");
                if (req.getCreditPack() != 0 && req.getCreditPack() != order.getCreditPack()) {
                    throw new RuntimeException("Payment credit pack mismatch");
                }

                var userSnap = transaction.get(userRef).get();
                if (!userSnap.exists()) throw new RuntimeException("User not found");
                Balances before = balances(userSnap.toObject(User.class));
                Balances after = new Balances(before.purchased() + order.getPurchasedCredits(), before.bonus() + order.getBonusCredits());

                WalletTransaction walletTx = txBuilder(paymentTxId, uid, "RECHARGE", order.getCreditPack(), before, after,
                        order.getCreditPack() + " credit pack purchased", now)
                        .purchasedDelta(order.getPurchasedCredits())
                        .bonusDelta(order.getBonusCredits())
                        .razorpayOrderId(order.getOrderId())
                        .razorpayPaymentId(req.getRazorpayPaymentId())
                        .build();

                transaction.update(userRef,
                        "purchasedCredits", after.purchased(),
                        "bonusCredits", after.bonus(),
                        "walletCredits", after.total());
                transaction.update(orderRef,
                        "status", "PAID",
                        "razorpayPaymentId", req.getRazorpayPaymentId(),
                        "paidAt", now);
                transaction.set(txRef, walletTx);

                return Dto.VerifyPaymentResponse.builder()
                        .success(true)
                        .newBalance(after.total())
                        .purchasedCredits(after.purchased())
                        .bonusCredits(after.bonus())
                        .message(order.getCreditPack() + " credits added successfully! Happy interviewing.")
                        .build();
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Payment credit transaction failed", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private RedeemRequest updateRedeemStatus(String requestId, String expectedStatus, String targetStatus,
                                             Dto.AdminRedeemActionRequest action, boolean refund) {
        long now = System.currentTimeMillis();
        var reqRef = firestore.collection(REDEEM_REQUESTS_COLLECTION).document(requestId);
        try {
            return firestore.runTransaction(transaction -> {
                var reqSnap = transaction.get(reqRef).get();
                if (!reqSnap.exists()) throw new RuntimeException("Redeem request not found");
                RedeemRequest redeem = reqSnap.toObject(RedeemRequest.class);
                if (redeem == null) throw new RuntimeException("Invalid redeem request");
                redeem.setId(requestId);
                if ("DONE".equals(redeem.getStatus())) throw new RuntimeException("Redeem request is already done");
                if (expectedStatus != null && !expectedStatus.equals(redeem.getStatus())) {
                    throw new RuntimeException("Redeem request must be " + expectedStatus + " before this action");
                }
                if (refund && !"PENDING".equals(redeem.getStatus()) && !"APPROVED".equals(redeem.getStatus())) {
                    throw new RuntimeException("Only pending or approved requests can be rejected");
                }

                redeem.setStatus(targetStatus);
                redeem.setUpdatedAt(now);
                redeem.setAdminNote(action != null ? action.getAdminNote() : redeem.getAdminNote());
                if (action != null && !isBlank(action.getPayoutId())) redeem.setPayoutId(action.getPayoutId());
                if ("APPROVED".equals(targetStatus)) redeem.setApprovedAt(now);
                if ("DONE".equals(targetStatus)) redeem.setDoneAt(now);
                if ("REJECTED".equals(targetStatus)) redeem.setRejectedAt(now);

                if (refund) refundPurchasedCredits(transaction, redeem, now);
                transaction.set(reqRef, redeem);
                if ("APPROVED".equals(targetStatus)) {
                    transaction.update(firestore.collection(USERS_COLLECTION).document(redeem.getUid()),
                            "activeRedeemStatus", "APPROVED");
                }
                if ("DONE".equals(targetStatus) || "REJECTED".equals(targetStatus)) {
                    transaction.update(firestore.collection(USERS_COLLECTION).document(redeem.getUid()),
                            "activeRedeemRequestId", null,
                            "activeRedeemStatus", null);
                }
                return redeem;
            }).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Redeem update failed", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) throw runtimeException;
            throw new RuntimeException("Redeem update failed", e);
        }
    }

    private void refundPurchasedCredits(com.google.cloud.firestore.Transaction transaction, RedeemRequest redeem, long now) throws Exception {
        var userRef = firestore.collection(USERS_COLLECTION).document(redeem.getUid());
        var userSnap = transaction.get(userRef).get();
        if (!userSnap.exists()) throw new RuntimeException("User not found for refund");
        Balances before = balances(userSnap.toObject(User.class));
        Balances after = new Balances(before.purchased() + redeem.getAmount() + TOTAL_FEE, before.bonus());
        String txId = "redeem_refund_" + redeem.getId();
        var txRef = firestore.collection(WALLET_TRANSACTIONS_COLLECTION).document(txId);
        var txSnap = transaction.get(txRef).get();
        if (txSnap.exists()) return;
        WalletTransaction walletTx = txBuilder(txId, redeem.getUid(), "REDEEM_REFUND", redeem.getAmount() + TOTAL_FEE, before, after,
                "Redeem request rejected. Purchased credits refunded including fee.", now)
                .purchasedDelta(redeem.getAmount() + TOTAL_FEE)
                .redeemRequestId(redeem.getId())
                .build();
        transaction.update(userRef,
                "purchasedCredits", after.purchased(),
                "bonusCredits", after.bonus(),
                "walletCredits", after.total());
        transaction.set(txRef, walletTx);
    }

    private String triggerPayoutBestEffort(RedeemRequest redeem) {
        String keyId = props.getRazorpay().getKeyId();
        String keySecret = props.getRazorpay().getKeySecret();
        String accountNumber = "2323230079048995";
            //props.getRazorpay().getAccountNumber();
        if (isBlank(keyId) || isBlank(keySecret) ) {
            log.warn("Razorpay payout skipped for redeem {} because payout config is incomplete", redeem.getId());
            return null;
        }
        User user = userRepository.findById(redeem.getUid())
            .orElseThrow(() -> new RuntimeException("User not found"));

    String fundAccountId = ensureFundAccount(user, redeem.getUpiId());
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payout = webClientBuilder.build()
                    .post()
                    .uri("https://api.razorpay.com/v1/payouts")
                    .headers(headers -> {
                        headers.setBasicAuth("rzp_test_SnAx5M4DxpnxRw", "kvPgPCEL2o0bLfCLoGDEJGpo");
                        headers.add("X-Payout-Idempotency", redeem.getId());
                    })
                    .bodyValue(Map.of(
                        "account_number", accountNumber,
                        "fund_account_id", fundAccountId,
                        "amount", redeem.getAmount() * 100,
                        "currency", "INR",
                        "mode", "UPI",
                        "purpose", "payout",
                        "queue_if_low_balance", true,
                        "reference_id", redeem.getId(),
                        "narration", "AssessArc"
                ))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            String payoutId = payout != null ? String.valueOf(payout.get("id")) : null;
            if (!isBlank(payoutId)) {
                firestore.collection(REDEEM_REQUESTS_COLLECTION).document(redeem.getId()).update("payoutId", payoutId).get();
                redeem.setPayoutId(payoutId);
                return payoutId;
            }
        } catch (Exception e) {
            log.error("Razorpay payout failed for redeem {}: {}", redeem.getId(), e.getMessage());
        }
        return null;
    }

    private WalletTransaction.WalletTransactionBuilder txBuilder(String id, String uid, String type, int amount,
                                                                 Balances before, Balances after,
                                                                 String description, long createdAt) {
        return WalletTransaction.builder()
                .id(id)
                .uid(uid)
                .type(type)
                .amount(amount)
                .balanceBefore(before.total())
                .balanceAfter(after.total())
                .purchasedBefore(before.purchased())
                .purchasedAfter(after.purchased())
                .bonusBefore(before.bonus())
                .bonusAfter(after.bonus())
                .description(description)
                .createdAt(createdAt);
    }

    private Balances balances(User user) {
        if (user == null) return new Balances(0, 0);
        int purchased = Math.max(0, user.getPurchasedCredits());
        int bonus = Math.max(0, user.getBonusCredits());
        if (purchased == 0 && bonus == 0 && user.getWalletCredits() > 0) {
            purchased = user.getWalletCredits();
        }
        return new Balances(purchased, bonus);
    }

    private Plan planFor(int creditPack) {
        return PLANS.stream()
                .filter(plan -> plan.totalCredits() == creditPack)
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Invalid credit pack: " + creditPack
                        + ". Valid: 10, 35, 70, 115, 220"));
    }

    private void savePaymentOrder(PaymentOrder order) {
        try {
            firestore.collection(PAYMENT_ORDERS_COLLECTION).document(order.getOrderId()).set(order).get();
        } catch (Exception e) {
            throw new RuntimeException("Payment order creation failed", e);
        }
    }

    private Dto.WalletTransactionItem toTransactionItem(WalletTransaction tx) {
        return Dto.WalletTransactionItem.builder()
                .id(tx.getId())
                .type(tx.getType())
                .amount(tx.getAmount())
                .balanceBefore(tx.getBalanceBefore())
                .balanceAfter(tx.getBalanceAfter())
                .purchasedBefore(tx.getPurchasedBefore())
                .purchasedAfter(tx.getPurchasedAfter())
                .bonusBefore(tx.getBonusBefore())
                .bonusAfter(tx.getBonusAfter())
                .purchasedDelta(tx.getPurchasedDelta())
                .bonusDelta(tx.getBonusDelta())
                .description(tx.getDescription())
                .razorpayOrderId(tx.getRazorpayOrderId())
                .razorpayPaymentId(tx.getRazorpayPaymentId())
                .interviewId(tx.getInterviewId())
                .redeemRequestId(tx.getRedeemRequestId())
                .createdAt(tx.getCreatedAt())
                .build();
    }

    private Dto.RedeemRequestItem toRedeemItem(RedeemRequest req) {
        return Dto.RedeemRequestItem.builder()
                .id(req.getId())
                .uid(req.getUid())
                .userEmail(req.getUserEmail())
                .upiId(req.getUpiId())
                .amount(req.getAmount())
                .status(req.getStatus())
                .payoutId(req.getPayoutId())
                .adminNote(req.getAdminNote())
                .createdAt(req.getCreatedAt())
                .updatedAt(req.getUpdatedAt())
                .build();
    }

    private String paymentTransactionId(String paymentId) {
        if (isBlank(paymentId)) throw new RuntimeException("Payment ID is required");
        return "rzp_" + paymentId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private boolean verifySignature(String orderId, String paymentId, String signature) {
        try {
            if (isBlank(orderId) || isBlank(paymentId) || isBlank(signature) || isBlank(props.getRazorpay().getKeySecret())) return false;
            String payload = orderId + "|" + paymentId;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.getRazorpay().getKeySecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8))).equalsIgnoreCase(signature);
        } catch (Exception e) {
            log.error("Signature verification error: {}", e.getMessage());
            return false;
        }
    }

    private String ensureFundAccount(User user, String upiId) {

      if (!isBlank(user.getRazorpayFundAccountId())) {
          return user.getRazorpayFundAccountId();
      }

      String keyId = props.getRazorpay().getKeyId();
      String keySecret = props.getRazorpay().getKeySecret();

      try {

          String contactId = user.getRazorpayContactId();

          // CREATE CONTACT
          if (isBlank(contactId)) {

              @SuppressWarnings("unchecked")
              Map<String, Object> contactResponse = webClientBuilder.build()
                      .post()
                      .uri("https://api.razorpay.com/v1/contacts")
                      .headers(headers -> headers.setBasicAuth(keyId, keySecret))
                      .bodyValue(Map.of(
                              "name", user.getName() != null ? user.getName() : user.getUid(),
                              "email", user.getEmail() != null ? user.getEmail() : "support@assessarc.app",
                              "contact", "9000000000",
                              "type", "customer"
                      ))
                      .retrieve()
                      .bodyToMono(Map.class)
                      .timeout(Duration.ofSeconds(30))
                      .block();

              contactId = String.valueOf(contactResponse.get("id"));

              firestore.collection(USERS_COLLECTION)
                      .document(user.getUid())
                      .update("razorpayContactId", contactId)
                      .get();
          }

          // CREATE FUND ACCOUNT

          @SuppressWarnings("unchecked")
          Map<String, Object> fundResponse = webClientBuilder.build()
                  .post()
                  .uri("https://api.razorpay.com/v1/fund_accounts")
                  .headers(headers -> headers.setBasicAuth(keyId, keySecret))
                  .bodyValue(Map.of(
                          "contact_id", contactId,
                          "account_type", "vpa",
                          "vpa", Map.of(
                                  "address", upiId
                          )
                  ))
                  .retrieve()
                  .bodyToMono(Map.class)
                  .timeout(Duration.ofSeconds(30))
                  .block();

          String fundAccountId = String.valueOf(fundResponse.get("id"));

          firestore.collection(USERS_COLLECTION)
                  .document(user.getUid())
                  .update("razorpayFundAccountId", fundAccountId)
                  .get();

          return fundAccountId;

      } catch (Exception e) {
          throw new RuntimeException("Failed to create Razorpay fund account: " + e.getMessage(), e);
      }
  }
    private String normalizeUpi(String upi) {
        return upi == null ? "" : upi.trim().toLowerCase();
    }

    private void validateUpi(String upi) {
        if (isBlank(upi) || !UPI_PATTERN.matcher(upi).matches()) {
            throw new RuntimeException("Enter a valid UPI ID");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
