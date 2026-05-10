package com.assessarc.repository;

import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Query;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.assessarc.model.Feedback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.*;
import java.util.concurrent.ExecutionException;

@Slf4j
@Repository
@RequiredArgsConstructor
public class FeedbackRepository {

    private static final String COLLECTION = "feedbacks";
    private final Firestore firestore;

    public void save(Feedback feedback) {
        try {
            // Let Firestore auto-generate ID
            var docRef = firestore.collection(COLLECTION).document();
            feedback.setId(docRef.getId());
            docRef.set(feedback).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error saving feedback: {}", e.getMessage());
            throw new RuntimeException("Failed to save feedback", e);
        }
    }

    public List<Feedback> findByTypeOrderByCreatedAtDesc(String type) {
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection(COLLECTION)
                    .whereEqualTo("type", type)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(100)
                    .get().get().getDocuments();

            List<Feedback> list = new ArrayList<>();
            for (var doc : docs) {
                Feedback fb = doc.toObject(Feedback.class);
                if (fb != null) {
                    fb.setId(doc.getId());
                    list.add(fb);
                }
            }
            return list;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching feedback by type {}: {}", type, e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<Feedback> findAllOrderByCreatedAtDesc() {
        try {
            List<QueryDocumentSnapshot> docs = firestore.collection(COLLECTION)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
                    .limit(200)
                    .get().get().getDocuments();

            List<Feedback> list = new ArrayList<>();
            for (var doc : docs) {
                Feedback fb = doc.toObject(Feedback.class);
                if (fb != null) {
                    fb.setId(doc.getId());
                    list.add(fb);
                }
            }
            return list;
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error fetching all feedbacks: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public void markRead(String id) {
        try {
            firestore.collection(COLLECTION).document(id).update("isRead", true).get();
        } catch (InterruptedException | ExecutionException e) {
            log.error("Error marking feedback {} as read: {}", id, e.getMessage());
        }
    }
}
