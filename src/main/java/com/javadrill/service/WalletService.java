package com.javadrill.service;

import com.google.cloud.firestore.Firestore;
import com.javadrill.config.AppProperties;
import com.javadrill.dto.Dto;
import com.javadrill.model.PaymentOrder;
import com.javadrill.model.User;
import com.javadrill.model.WalletTransaction;
import com.javadrill.repository.UserRepository;
import com.javadrill.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {

    private static final String PAYMENT_ORDERS_COLLECTION = "payment_orders";
    private static final String USERS_COLLECTION = "users";
    private static final String WALLET_TRANSACTIONS_COLLECTION = "wallet_transactions";

    private final UserRepository userRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final AppProperties props;
    private final WebClient.Builder webClientBuilder;
    private final Firestore firestore;

    public Dto.WalletBalanceResponse getBalance(String uid) {
        var user = userRepository.findById(uid)
                .orElseThrow(() -> new RuntimeException("User not found"));
        return Dto.WalletBalanceResponse.builder()
                .credits(user.getWalletCredits())
                .build();
    }

    public List<Dto.WalletTransactionItem> getHistory(String uid) {
        return walletTransactionRepository.findByUid(uid, 20).stream()
                .map(this::toTransactionItem)
                .collect(Collectors.toList());
    }

    public Dto.CreateOrderResponse createOrder(String uid, int creditPack) {
        if (uid == null || uid.isBlank()) {
            throw new RuntimeException("Unauthorized");
        }
        int amountPaise = switch (creditPack) {
            case 10 -> 1000;
            case 25 -> 2400;
            case 50 -> 4500;
            case 100 -> 8000;
            default -> throw new RuntimeException("Invalid credit pack: " + creditPack
                    + ". Valid: 10, 25, 50, 100");
        };

        String keyId = props.getRazorpay().getKeyId();
        String keySecret = props.getRazorpay().getKeySecret();
        if (keyId == null || keyId.isBlank() || keySecret == null || keySecret.isBlank()) {
            throw new RuntimeException("Payment gateway is not configured");
        }

        String receipt = "jd_" + uid.substring(0, Math.min(8, uid.length())) + "_"
                + creditPack + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        Map<String, Object> order;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> createdOrder = webClientBuilder.build()
                    .post()
                    .uri("https://api.razorpay.com/v1/orders")
                    .headers(headers -> headers.setBasicAuth(keyId, keySecret))
                    .bodyValue(Map.of(
                            "amount", amountPaise,
                            "currency", "INR",
                            "receipt", receipt
                    ))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            order = createdOrder;
        } catch (Exception e) {
            log.error("Razorpay order creation failed for user {} pack {}: {}", uid, creditPack, e.getMessage());
            throw new RuntimeException("Payment order creation failed. Please try again.");
        }

        String orderId = order != null ? String.valueOf(order.get("id")) : null;
        if (orderId == null || orderId.isBlank() || "null".equals(orderId)) {
            throw new RuntimeException("Payment order creation failed");
        }

        savePaymentOrder(PaymentOrder.builder()
                .orderId(orderId)
                .uid(uid)
                .creditPack(creditPack)
                .amountPaise(amountPaise)
                .currency("INR")
                .receipt(receipt)
                .status("CREATED")
                .createdAt(System.currentTimeMillis())
                .build());

        log.info("Created order {} for user {} ({} credits, INR {})",
                orderId, uid, creditPack, amountPaise / 100);

        return Dto.CreateOrderResponse.builder()
                .orderId(orderId)
                .amount(amountPaise)
                .currency("INR")
                .keyId(props.getRazorpay().getKeyId())
                .build();
    }

    public Dto.VerifyPaymentResponse verifyPayment(String uid, Dto.VerifyPaymentRequest req) {
        if (uid == null || uid.isBlank()) {
            throw new RuntimeException("Unauthorized");
        }
        if (req == null
                || isBlank(req.getRazorpayOrderId())
                || isBlank(req.getRazorpayPaymentId())
                || isBlank(req.getRazorpaySignature())) {
            throw new RuntimeException("Payment verification details are required");
        }
        String keySecret = props.getRazorpay().getKeySecret();
        if (keySecret == null || keySecret.isBlank()) {
            throw new RuntimeException("Payment gateway is not configured");
        }

        boolean valid = verifySignature(
                req.getRazorpayOrderId(),
                req.getRazorpayPaymentId(),
                req.getRazorpaySignature());

        if (!valid) {
            log.warn("Invalid Razorpay signature for user {} order {}", uid, req.getRazorpayOrderId());
            return Dto.VerifyPaymentResponse.builder()
                    .success(false)
                    .message("Payment verification failed. Please contact support.")
                    .build();
        }

        Dto.VerifyPaymentResponse response = creditWalletForPayment(uid, req);
        log.info("Payment verified for user {} order {} payment {}. New balance: {}",
                uid, req.getRazorpayOrderId(), req.getRazorpayPaymentId(), response.getNewBalance());
        return response;
    }

    private void savePaymentOrder(PaymentOrder order) {
        try {
            firestore.collection(PAYMENT_ORDERS_COLLECTION)
                    .document(order.getOrderId())
                    .set(order)
                    .get();
        } catch (Exception e) {
            log.error("Failed to persist payment order {}: {}", order.getOrderId(), e.getMessage());
            throw new RuntimeException("Payment order creation failed", e);
        }
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
                    if (existing != null && uid.equals(existing.getUid())
                            && req.getRazorpayOrderId().equals(existing.getRazorpayOrderId())) {
                        return Dto.VerifyPaymentResponse.builder()
                                .success(true)
                                .newBalance(existing.getBalanceAfter())
                                .message(existing.getAmount() + " credits already added.")
                                .build();
                    }
                    throw new RuntimeException("Payment has already been used");
                }

                var orderSnap = transaction.get(orderRef).get();
                if (!orderSnap.exists()) {
                    throw new RuntimeException("Payment order not found");
                }
                PaymentOrder order = orderSnap.toObject(PaymentOrder.class);
                if (order == null) {
                    throw new RuntimeException("Invalid payment order");
                }
                if (!uid.equals(order.getUid())) {
                    throw new RuntimeException("Payment order does not belong to this user");
                }
                if (!"CREATED".equals(order.getStatus())) {
                    throw new RuntimeException("Payment order is already processed");
                }
                if (req.getCreditPack() != 0 && req.getCreditPack() != order.getCreditPack()) {
                    throw new RuntimeException("Payment credit pack mismatch");
                }

                var userSnap = transaction.get(userRef).get();
                if (!userSnap.exists()) {
                    throw new RuntimeException("User not found");
                }
                User user = userSnap.toObject(User.class);
                int balanceBefore = user != null ? user.getWalletCredits() : 0;
                int newBalance = balanceBefore + order.getCreditPack();

                WalletTransaction walletTx = WalletTransaction.builder()
                        .id(paymentTxId)
                        .uid(uid)
                        .type("credit")
                        .amount(order.getCreditPack())
                        .balanceBefore(balanceBefore)
                        .balanceAfter(newBalance)
                        .description(order.getCreditPack() + " credit pack purchased")
                        .razorpayOrderId(order.getOrderId())
                        .razorpayPaymentId(req.getRazorpayPaymentId())
                        .createdAt(now)
                        .build();

                transaction.update(userRef, "walletCredits", newBalance);
                transaction.update(orderRef,
                        "status", "PAID",
                        "razorpayPaymentId", req.getRazorpayPaymentId(),
                        "paidAt", now);
                transaction.set(txRef, walletTx);

                return Dto.VerifyPaymentResponse.builder()
                        .success(true)
                        .newBalance(newBalance)
                        .message(order.getCreditPack() + " credits added successfully! Happy interviewing.")
                        .build();
            }).get();
        } catch (Exception e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            log.error("Payment credit transaction failed: {}", e.getMessage());
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private String paymentTransactionId(String paymentId) {
        if (paymentId == null || paymentId.isBlank()) {
            throw new RuntimeException("Payment ID is required");
        }
        return "rzp_" + paymentId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    public void recordDebit(String uid, int amount, int balanceBefore, int balanceAfter,
                            String description, String interviewId) {
        walletTransactionRepository.save(WalletTransaction.builder()
                .uid(uid)
                .type("debit")
                .amount(amount)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .description(description)
                .interviewId(interviewId)
                .createdAt(System.currentTimeMillis())
                .build());
    }

    public void recordCredit(String uid, int amount, int balanceBefore, int balanceAfter,
                             String description) {
        walletTransactionRepository.save(WalletTransaction.builder()
                .uid(uid)
                .type("credit")
                .amount(amount)
                .balanceBefore(balanceBefore)
                .balanceAfter(balanceAfter)
                .description(description)
                .createdAt(System.currentTimeMillis())
                .build());
    }

    private boolean verifySignature(String orderId, String paymentId, String signature) {
        try {
            if (isBlank(orderId) || isBlank(paymentId) || isBlank(signature)
                    || isBlank(props.getRazorpay().getKeySecret())) return false;
            String payload = orderId + "|" + paymentId;
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec key = new SecretKeySpec(
                    props.getRazorpay().getKeySecret().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256");
            mac.init(key);
            byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computed = HexFormat.of().formatHex(hash);
            return computed.equalsIgnoreCase(signature);
        } catch (Exception e) {
            log.error("Signature verification error: {}", e.getMessage());
            return false;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private Dto.WalletTransactionItem toTransactionItem(WalletTransaction tx) {
        return Dto.WalletTransactionItem.builder()
                .id(tx.getId())
                .type(tx.getType())
                .amount(tx.getAmount())
                .balanceBefore(tx.getBalanceBefore())
                .balanceAfter(tx.getBalanceAfter())
                .description(tx.getDescription())
                .razorpayOrderId(tx.getRazorpayOrderId())
                .razorpayPaymentId(tx.getRazorpayPaymentId())
                .interviewId(tx.getInterviewId())
                .createdAt(tx.getCreatedAt())
                .build();
    }
}
