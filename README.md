# JavaDrill Backend — Fixed & Production-Ready

## Spring Boot + Firestore AI Interview Platform

---

## Bugs Fixed in This Version

### Critical Fixes
1. **Firestore nested array update bug** — `InterviewRepository.updateAnswerAndFeedback()` was trying to update nested array fields using dotted path notation which Firestore doesn't support for array elements. Fixed to read-modify-write the full `questions` list in a transaction.

2. **Inconsistent `userId`/`uid` field** — `InterviewRepository` used `uid` in some queries and `userId` in others, causing Firestore queries to return empty results. All queries now consistently use `userId`.

3. **Performance analysis field mismatch** — `PerformanceAnalysisService` was querying Firestore with `"uid"` field but `Interview` model stored `"userId"`. Fixed to use `findAllCompletedByUserId()`.

4. **Auto-credit without payment** — `WalletService.verifyPayment()` now ALWAYS requires valid Razorpay HMAC-SHA256 signature. Removed any path where credits could be added without real payment.

5. **PDF parsing wrong API** — `ResumeService` used `Loader.loadPDF(file.getInputStream())` without reading all bytes first. Fixed to `file.getInputStream().readAllBytes()` with error fallback.

6. **Feedback `Instant` Firestore serialization** — Firestore can't serialize/deserialize Java `Instant` cleanly. Changed `Feedback.createdAt` to `long` epoch millis.

7. **Question bank user-specific dedup missing** — Each user can accumulate a `seenQuestionIds` list. When building a session, bank questions seen before by THAT user are excluded. This gives human-like fresh interviews.

8. **No cached resume summary** — Every `startInterview()` call was re-parsing the resume through Gemini (wasting API calls). Now cached in `user.resumeSummary` and only re-parsed when a new resume is uploaded.

9. **InterviewDetail returning null dates** — Fixed by checking `completedAt > 0` before formatting, falling back to `startedAt`.

10. **Missing `isActive` filter** — Question bank queries now filter `isActive=true` to allow disabling bad questions.

### Minor Fixes
- Signup bonus corrected to 10 credits (= 1 free 60-min session)
- `localhost:5173` added to CORS (for Vite dev server)
- JSON `null` exclusion configured (`spring.jackson.default-property-inclusion: non_null`)
- Question bank dedup now matches across categories (not just same-category)
- `Feedback` model ID set from Firestore auto-generated document ID

---

## API Endpoints

### Auth
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/auth/login` | Bearer token | Login/register via Firebase ID token |
| GET | `/api/auth/me` | Bearer token | Get current user profile |

### Interview
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/interview/start` | ✅ | Start session (deducts credits, generates questions) |
| POST | `/api/interview/submit` | ✅ | Submit answer → get AI feedback |
| POST | `/api/interview/complete` | ✅ | Score interview, save to history |
| GET | `/api/interview/history` | ✅ | Last 10 completed interviews |
| GET | `/api/interview/history/{id}` | ✅ | Full detail of one interview |

### Resume
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/resume/upload` | ✅ | Upload PDF/TXT resume (multipart) |

### Wallet
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/wallet/balance` | ✅ | Get credit balance |
| POST | `/api/wallet/order` | ✅ | Create Razorpay order |
| POST | `/api/wallet/verify` | ✅ | Verify payment + credit wallet |

### Performance
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| GET | `/api/performance/analysis` | ✅ | AI analysis of last 7 sessions |

### Feedback
| Method | Path | Auth | Description |
|--------|------|------|-------------|
| POST | `/api/feedback` | ✅ | Submit feedback from dashboard |
| POST | `/api/contact` | ❌ (public) | Contact us form |
| GET | `/api/admin/feedback` | ✅ | Admin: all feedback |
| GET | `/api/admin/contacts` | ✅ | Admin: all contact submissions |

---

## Pricing Logic

| Duration | Credits Deducted | Equivalent |
|----------|-----------------|------------|
| 30 min | 5 credits | ₹5 |
| 60 min | 10 credits | ₹10 |

Credit Packs (via Razorpay):
| Pack | Price |
|------|-------|
| 10 credits | ₹10 |
| 25 credits | ₹24 |
| 50 credits | ₹45 |
| 100 credits | ₹80 |

Signup bonus: **10 credits** (1 free interview)

---

## Firestore Collections

| Collection | Description |
|------------|-------------|
| `users` | User profiles, wallet, resume, stats, seenQuestionIds |
| `interviews` | Interview sessions with Q&A and scores |
| `question_bank` | Central shared question bank (grows over time) |
| `feedbacks` | Dashboard feedback + contact-us messages |

### Required Firestore Indexes
Create these composite indexes in Firebase console:

1. `interviews` → `userId` ASC + `status` ASC + `completedAt` DESC
2. `question_bank` → `category` ASC + `isActive` ASC
3. `feedbacks` → `type` ASC + `createdAt` DESC

---

## Setup

```bash
# 1. Update application.yml with your actual credentials
# (already filled with your Gemini, Firebase, Razorpay keys)

# 2. Build
mvn clean package -DskipTests

# 3. Run
java -jar target/javadrill-backend-1.0.0.jar

# Server starts on port 8080
```

### To Integrate Real Razorpay (Replace Mock Order)
In `WalletService.createOrder()`, uncomment the Razorpay SDK block and add dependency to pom.xml:
```xml
<dependency>
    <groupId>com.razorpay</groupId>
    <artifactId>razorpay-java</artifactId>
    <version>1.4.3</version>
</dependency>
```
