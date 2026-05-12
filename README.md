# AssessArc - AI-Powered Java Mock Interview Platform

[![Java](https://img.shields.io/badge/Java-17-orange)](https://openjdk.java.net/projects/jdk/17/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.0-brightgreen)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18.2.0-blue)](https://reactjs.org/)
[![Firebase](https://img.shields.io/badge/Firebase-9.2.0-yellow)](https://firebase.google.com/)
[![Gemini AI](https://img.shields.io/badge/Gemini--AI-2.0-red)](https://ai.google.dev/)

---

## 📋 Table of Contents

- [What is AssessArc?](#-what-is-assessarc)
- [Core Features](#-core-features)
- [Technology Stack](#-technology-stack)
- [User Journey Example](#-user-journey-example)
- [API Architecture](#-api-architecture)
- [Pricing & Monetization](#-pricing--monetization)
- [MVP (Minimum Viable Product)](#-mvp-minimum-viable-product)
- [MBP (Minimum Beautiful Product)](#-mbp-minimum-beautiful-product)
- [What Attracts Users](#-what-attracts-users)
- [Potential Pain Points](#-potential-pain-points)
- [Installation & Setup](#-installation--setup)
- [API Documentation](#-api-documentation)
- [Database Schema](#-database-schema)
- [Security & Authentication](#-security--authentication)
- [Deployment](#-deployment)
- [Contributing](#-contributing)

---

## 🎯 What is AssessArc?

**AssessArc** is an AI-powered mock interview platform specifically designed for Java developers preparing for technical interviews. Unlike generic interview prep tools, AssessArc offers:

- **Voice-first interviews** with Sarah AI (conversational, human-like experience)
- **Resume-tailored questions** based on your actual experience and tech stack
- **Real-time feedback** after each answer with specific improvement suggestions
- **Performance analytics** with trend tracking and category-wise insights
- **Credit-based pay-per-session** model (no subscriptions)

The platform simulates realistic Java developer interviews with questions ranging from basic Java concepts to advanced topics like Spring Boot, microservices, system design, and behavioral questions.

---

## 🚀 Core Features

### For Interview Candidates
- **🎤 Voice-Enabled Interviews**: Sarah AI conducts interviews using speech synthesis and recognition
- **📄 Resume Integration**: Upload PDF/TXT resume for personalized question generation
- **🧠 AI Feedback**: Instant feedback with one strength and one improvement point per answer
- **📊 Performance Analytics**: Track communication, technical depth, problem-solving skills
- **🔄 Fresh Question Sets**: No repeats - system tracks seen questions per user
- **💰 Flexible Pricing**: Pay only for sessions you want (credit-based system)
- **📱 Responsive Design**: Works on desktop and mobile devices

### For Administrators
- **👥 User Management**: View all users, their activity, and wallet balances
- **💳 Payout Management**: Approve/reject redemption requests with automatic Razorpay integration
- **📈 System Monitoring**: Track Gemini AI usage, API costs, and system health
- **💬 Feedback Management**: Review user feedback and contact form submissions
- **🔧 Admin Dashboard**: Comprehensive admin panel for system management

### Technical Features
- **Firebase Authentication**: Secure login with Google/email
- **Firestore Database**: Scalable NoSQL database for user data and interviews
- **Gemini AI Integration**: Google's latest AI model for question generation and feedback
- **Razorpay Payment Gateway**: Secure Indian payment processing
- **WebRTC Voice Processing**: Real-time speech recognition and synthesis
- **JWT Security**: Stateless authentication with refresh tokens

---

## 🛠 Technology Stack

### Backend (Spring Boot)
- **Framework**: Spring Boot 3.2.0 with Java 17
- **Database**: Google Firestore (NoSQL)
- **Authentication**: Firebase Auth + JWT
- **AI Integration**: Google Gemini 2.0 Flash Lite
- **Payments**: Razorpay API
- **Security**: Spring Security with CORS
- **Build Tool**: Maven
- **Deployment**: Render.com (cloud hosting)

### Frontend (React)
- **Framework**: React 18.2.0 with Hooks
- **Routing**: React Router v6
- **Styling**: CSS Variables (custom design system)
- **Charts**: Recharts for analytics
- **Voice**: Web Speech API + WebRTC
- **Build Tool**: Create React App
- **Deployment**: Vercel (CDN hosting)

### External Services
- **Firebase**: Authentication, Firestore database, hosting
- **Gemini AI**: Question generation, feedback analysis, resume parsing
- **Razorpay**: Payment processing, payout management
- **Web Speech API**: Voice recognition and synthesis

---

## 👤 User Journey Example

Let's follow **Rahul**, a 3-year experienced Java developer preparing for FAANG interviews:

### Step 1: Discovery & Signup
Rahul finds AssessArc through Google search for "Java interview preparation". He visits the landing page and sees:
- Clear value proposition: "Practice Java interviews with AI that sounds human"
- Social proof and testimonials
- Free signup with 10 credits (worth ₹10)

He signs up with Google OAuth and gets instant access to his dashboard.

### Step 2: Profile Setup
```
Dashboard → Resume Upload
```
Rahul uploads his resume (PDF format). The system:
- Parses his 3 years experience at Infosys
- Identifies his tech stack: Java 8/11, Spring Boot, Hibernate, MySQL
- Extracts projects: E-commerce platform, Payment gateway integration
- Caches summary for future interviews

### Step 3: First Interview Session
```
Dashboard → Start Interview → Select 30-minute session
```
- **Cost**: 10 credits (₹10 equivalent)
- **Question Types**: Mix of technical + behavioral
- **Voice Interaction**: Sarah AI greets him and starts asking questions

**Sample Interview Flow:**
1. **Sarah**: "Hi Rahul, I see you've worked on Spring Boot microservices. Can you explain how you handle service discovery in a distributed system?"

2. **Rahul**: Speaks his answer (2-3 minutes)

3. **AI Feedback**: "Strength: Good understanding of Eureka server. Improvement: Mention specific configuration parameters."

4. **Next Question**: System generates fresh question based on his resume

### Step 4: Post-Interview Analysis
```
Interview Complete → View Report
```
- **Overall Score**: 7.5/10
- **Category Breakdown**:
  - Communication: 8/10
  - Technical Depth: 7/10
  - Problem Solving: 8/10
- **Detailed Feedback**: Specific suggestions for improvement
- **Question History**: All Q&A with timestamps

### Step 5: Performance Tracking
```
Dashboard → Performance Analytics
```
- **Trend Charts**: Shows improvement over time
- **Category Radar**: Visual breakdown of strengths/weaknesses
- **Historical Data**: Last 7 interviews with scores
- **Insights**: "Your Spring Boot answers improved 15% this week"

### Step 6: Skill Development Loop
Rahul identifies weak areas (System Design) and practices more sessions:
- Purchases credit pack: ₹59 for 70 total credits (60 purchased + 10 bonus)
- Focuses on specific categories
- Tracks progress with detailed analytics

### Step 7: Advanced Features
As Rahul progresses:
- **Redeem Earnings**: Converts credits to cash via UPI (with Razorpay fees)
- **Long-term Tracking**: Views 6-month performance trends
- **Custom Sessions**: Requests specific topic focus

---

## 📡 API Architecture

### Authentication Flow
```
Client → Firebase Auth → ID Token → Backend → JWT Token
```

### Interview Flow
```
1. POST /api/interview/start
   → Validate credits → Generate questions → Deduct credits

2. POST /api/interview/submit (per question)
   → Gemini AI analysis → Store feedback

3. POST /api/interview/complete
   → Calculate final scores → Save to history
```

### Payment Flow
```
Credit Purchase:
Client → Create Order → Razorpay Checkout → Payment Success → Verify → Credit Wallet

Redemption:
User → Redeem Request → Admin Approval → Razorpay Payout → Status Update
```

### Data Flow
```
Resume Upload → Gemini Parsing → Cached Summary
Questions → User Tracking → No Repeats
Answers → AI Feedback → Performance Analytics
```

---

## 💰 Pricing & Monetization

### Credit System
- **1 Credit = ₹1** (simplified pricing)
- **Session Costs**:
  - 30 minutes: 10 credits
  - 60 minutes: 15 credits

### Credit Packs (Razorpay Integration)
| Pack | Credits | Price | Bonus | Value | Sessions (30/60min) |
|------|---------|-------|-------|-------|------------------|
| Single | 10 | ₹10 | 0 | 100% | 1 / 0 |
| Starter | 35 | ₹29 | 5 | 138% | 3 / 2 |
| **Pro** | 70 | ₹59 | 10 | 136% | 7 / 4 |
| Elite | 115 | ₹99 | 15 | 142% | 11 / 7 |
| Titan | 220 | ₹199 | 20 | 121% | 22 / 14 |

### Revenue Streams
1. **Session Credits**: Primary revenue from interview sessions
2. **Premium Features**: Future expansion (advanced analytics, etc.)
3. **Redemption Fees**: Razorpay charges ₹2.5 + 18% GST per payout

### Freemium Model
- **Free Tier**: 10 credits on signup (1 quick 30-minute interview)
- **Conversion**: Free users become paying customers
- **Retention**: Analytics and progress tracking encourage continued use

---

## 🎯 MVP (Minimum Viable Product)

### Core Requirements Met
✅ **User Authentication** (Firebase Auth)
✅ **Resume Upload & Parsing** (Gemini AI)
✅ **Basic Interview Sessions** (30/60 min with voice)
✅ **AI Feedback** (Simple feedback per answer)
✅ **Credit System** (Purchase, track, deduct)
✅ **Payment Integration** (Razorpay)
✅ **Admin Panel** (Basic user/redeem management)

### MVP Success Metrics
- **User Acquisition**: 100+ signups in first month
- **Engagement**: 70% of free users complete at least 1 interview
- **Conversion**: 30% free-to-paid conversion rate
- **Retention**: 50% of paid users return within 7 days
- **Satisfaction**: 4+ star rating from initial users

### MVP Limitations (Addressed in MBP)
- Basic UI/UX (functional but not polished)
- Limited analytics (only basic scores)
- No advanced features (custom questions, team accounts)
- Manual admin processes
- Basic error handling

---

## ✨ MBP (Minimum Beautiful Product)

### Enhanced Features
🎨 **Polished UI/UX**
- Modern design system with animations
- Mobile-first responsive design
- Dark/light theme support
- Improved onboarding flow

📊 **Advanced Analytics**
- Detailed performance dashboards
- Trend analysis with charts
- Category-wise improvement tracking
- Comparative analysis

🎤 **Enhanced Voice Experience**
- Better speech recognition accuracy
- Multiple AI voices/personalities
- Real-time conversation flow
- Interrupt handling

🔧 **Admin Automation**
- Automated payout processing
- Bulk user management
- System health monitoring
- Automated reporting

🌟 **Premium Features**
- Custom question sets
- Team/organization accounts
- Advanced resume analysis
- Interview recording/playback

### MBP Goals
- **User Satisfaction**: 4.8+ star rating
- **Retention**: 70% monthly active users
- **Revenue**: Consistent MRR growth
- **Scalability**: Handle 1000+ concurrent users
- **Market Position**: Leading AI interview prep platform

---

## 🎉 What Attracts Users

### Unique Value Propositions
1. **Voice-First Experience**: Unlike text-based platforms, conversational AI feels like real interviews
2. **Resume Personalization**: Questions match actual experience, not generic templates
3. **No Subscription Pressure**: Pay-per-session model vs expensive monthly subscriptions
4. **Indian Focus**: Optimized for Indian English, local payment methods (UPI)
5. **Fresh Content**: No repeating questions keeps practice engaging
6. **Progress Tracking**: Detailed analytics show real improvement over time

### Target Audience Appeal
- **Fresh Graduates**: Affordable practice before campus placements
- **Mid-level Developers**: Skill validation before job switches
- **Experienced Professionals**: FAANG/Senior role preparation
- **Bootcamp Graduates**: Structured practice to bridge theory-practice gap
- **Career Switchers**: Java-specific preparation for new roles

### Competitive Advantages
- **AI Quality**: Latest Gemini 2.0 model vs competitors' older AI
- **Cost Effective**: ₹59 for 70 total credits vs ₹999/month subscriptions
- **Local Optimization**: Indian English, UPI payments, local pricing
- **Technical Depth**: Java-specific questions vs generic coding interviews
- **Voice Technology**: More realistic than chat-based platforms

---

## ⚠️ Potential Pain Points

### Technical Challenges
1. **Voice Recognition Accuracy**: Web Speech API limitations in noisy environments
2. **AI Response Times**: Gemini API latency affects conversation flow
3. **Browser Compatibility**: Best experience on Chrome/Edge, limited on Safari
4. **Mobile Experience**: Voice features work better on desktop

### User Experience Issues
1. **Learning Curve**: Voice interaction requires adaptation
2. **Internet Dependency**: Requires stable connection for voice features
3. **Credit Management**: Users need to track and purchase credits
4. **Question Quality**: AI-generated questions may occasionally be off-target

### Business Challenges
1. **AI Costs**: Gemini API usage scales with user growth
2. **Payment Processing**: Razorpay fees and chargeback handling
3. **User Acquisition**: Competitive market with established players
4. **Content Quality**: Maintaining high-quality question bank

### Mitigation Strategies
- **Fallback Options**: Text input as backup for voice issues
- **Quality Assurance**: Human review of AI-generated content
- **Customer Support**: Responsive support for technical issues
- **Iterative Improvement**: Regular updates based on user feedback

---

## 🏗 Installation & Setup

### Prerequisites
- Java 17+
- Node.js 16+
- Maven 3.6+
- Firebase project with Firestore
- Razorpay account
- Google Cloud project with Vertex AI enabled

### Backend Setup
```bash
# Clone repository
git clone https://github.com/yourusername/assessarc-backend.git
cd assessarc-backend

# Configure environment variables
export VERTEX_AI_PROJECT_ID=your_google_cloud_project_id
export VERTEX_AI_LOCATION=us-central1
export VERTEX_AI_MODEL=gemini-2.5-flash-lite
export GOOGLE_APPLICATION_CREDENTIALS=/path/to/vertex-ai-service-account.json
export FIREBASE_SERVICE_ACCOUNT_PATH=/path/to/service-account.json
export RAZORPAY_KEY_ID=your_razorpay_key
export RAZORPAY_KEY_SECRET=your_razorpay_secret
export RAZORPAY_ACCOUNT_NUMBER=your_razorpay_account_number

# Build and run
mvn clean install
mvn spring-boot:run
```

### Frontend Setup
```bash
# Clone repository
git clone https://github.com/yourusername/assessarc-frontend.git
cd assessarc-frontend

# Install dependencies
npm install

# Configure environment
echo "REACT_APP_API_URL=http://localhost:8080" > .env.local

# Start development server
npm start
```

### Environment Configuration
```yaml
# application.yml
assessarc:
  gemini:
    # Previous direct Gemini API config is no longer required:
    # api-key: ${GEMINI_API_KEYS}
    vertex:
      enabled: true
      project-id: ${VERTEX_AI_PROJECT_ID}
      location: ${VERTEX_AI_LOCATION:us-central1}
      model: ${VERTEX_AI_MODEL:gemini-2.5-flash-lite}
      credentials-path: ${GOOGLE_APPLICATION_CREDENTIALS:}
  firebase:
    service-account-path: file:/etc/secrets/firebase-service-account.json
  razorpay:
    key-id: ${RAZORPAY_KEY_ID:}
    key-secret: ${RAZORPAY_KEY_SECRET:}
    account-number: ${RAZORPAY_ACCOUNT_NUMBER:}
```

### Vertex AI Production Setup
1. In Google Cloud, select the project that has your credits/billing attached.
2. Enable the **Vertex AI API** for that project.
3. Create a service account, for example `assessarc-vertex-ai`.
4. Grant it `Vertex AI User` (`roles/aiplatform.user`). If you want the same service account to read other Google Cloud resources later, add only the extra roles you actually need.
5. Create a JSON key for that service account and store it as a private deploy secret file, not in Git.
6. Set backend environment variables:
   - `VERTEX_AI_PROJECT_ID=your_google_cloud_project_id`
   - `VERTEX_AI_LOCATION=us-central1`
   - `VERTEX_AI_MODEL=gemini-2.5-flash-lite`
   - `GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/vertex-ai-service-account.json`
7. Keep `GEMINI_API_KEYS` unset for production. It is only kept in comments as rollback reference.
8. Deploy, then start one interview and check the admin Gemini monitoring page for successful token usage logs.

---

## 📚 API Documentation

### Authentication Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/auth/login` | Firebase ID token authentication |
| GET | `/api/auth/me` | Get current user profile |

### Interview Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/interview/start` | Start interview session |
| POST | `/api/interview/submit` | Submit answer for feedback |
| POST | `/api/interview/complete` | Complete interview and get scores |
| GET | `/api/interview/history` | Get interview history |
| GET | `/api/interview/history/{id}` | Get detailed interview |

### Wallet Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/wallet/balance` | Get credit balance |
| POST | `/api/wallet/order` | Create Razorpay order |
| POST | `/api/wallet/verify` | Verify payment |
| POST | `/api/wallet/redeem` | Request credit redemption |

### Admin Endpoints
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/admin/users` | List all users |
| GET | `/api/admin/redeems` | List redemption requests |
| POST | `/api/admin/redeems/{id}/approve` | Approve redemption |
| POST | `/api/admin/redeems/{id}/reject` | Reject redemption |

---

## 🗄 Database Schema

### Firestore Collections

#### `users`
```json
{
  "uid": "firebase_user_id",
  "email": "user@example.com",
  "displayName": "John Doe",
  "photoURL": "https://...",
  "purchasedCredits": 100,
  "bonusCredits": 10,
  "walletCredits": 110,
  "resumeSummary": "cached_gemini_summary",
  "upiId": "user@upi",
  "activeRedeemRequestId": "redeem_id",
  "activeRedeemStatus": "PENDING|APPROVED|REJECTED|DONE",
  "createdAt": 1640995200000
}
```

#### `interviews`
```json
{
  "id": "interview_id",
  "userId": "firebase_user_id",
  "questions": [
    {
      "id": "q1",
      "question": "Explain Spring Boot...",
      "category": "SPRING_BOOT",
      "answer": "User's answer...",
      "feedback": "Good explanation...",
      "score": 8.5
    }
  ],
  "finalScores": {
    "overall": 8.2,
    "communication": 8.5,
    "technical": 7.8,
    "problemSolving": 8.1
  },
  "startedAt": 1640995200000,
  "completedAt": 1640998800000,
  "duration": 60
}
```

#### `wallet_transactions`
```json
{
  "id": "tx_id",
  "uid": "user_id",
  "type": "RECHARGE|REDEEM_REQUEST|REDEEM_REFUND",
  "amount": 50,
  "description": "Credit pack purchase",
  "balanceBefore": 10,
  "balanceAfter": 60,
  "createdAt": 1640995200000
}
```

---

## 🔒 Security & Authentication

### Authentication Flow
1. **Client**: Firebase Auth (Google/Email)
2. **Token Exchange**: Firebase ID Token → Backend JWT
3. **Session Management**: Stateless JWT with refresh tokens
4. **Authorization**: Role-based access (USER/ADMIN)

### Security Measures
- **CORS Configuration**: Restricted to allowed domains
- **Input Validation**: Comprehensive request validation
- **Rate Limiting**: API rate limiting for abuse prevention
- **Data Encryption**: Sensitive data encrypted at rest
- **Audit Logging**: All admin actions logged

### Payment Security
- **Razorpay Integration**: PCI DSS compliant
- **Signature Verification**: HMAC-SHA256 payment verification
- **Idempotency**: Prevents duplicate transactions
- **Refund Protection**: Secure refund processing

---

## 🚀 Deployment

### Backend Deployment (Render.com)
```yaml
# render.yaml
services:
  - type: web
    name: assessarc-backend
    env: docker
    buildCommand: mvn clean package -DskipTests
    startCommand: java -jar target/*.jar
    envVars:
      - key: VERTEX_AI_PROJECT_ID
        value: your_google_cloud_project_id
      - key: VERTEX_AI_LOCATION
        value: us-central1
      - key: VERTEX_AI_MODEL
        value: gemini-2.5-flash-lite
      - key: GOOGLE_APPLICATION_CREDENTIALS
        value: /etc/secrets/vertex-ai-service-account.json
      - key: FIREBASE_SERVICE_ACCOUNT
        value: your_firebase_config
```

### Frontend Deployment (Vercel)
```json
// vercel.json
{
  "buildCommand": "npm run build",
  "outputDirectory": "build",
  "env": {
    "REACT_APP_API_URL": "https://assessarc-backend.onrender.com"
  }
}
```

### Environment Variables
```bash
# Backend
VERTEX_AI_PROJECT_ID=your_google_cloud_project_id
VERTEX_AI_LOCATION=us-central1
VERTEX_AI_MODEL=gemini-2.5-flash-lite
GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/vertex-ai-service-account.json
FIREBASE_SERVICE_ACCOUNT_PATH=/etc/secrets/service-account.json
RAZORPAY_KEY_ID=rzp_live_xxx
RAZORPAY_KEY_SECRET=your_secret

# Frontend
REACT_APP_API_URL=https://api.assessarc.app
REACT_APP_FIREBASE_CONFIG=your_firebase_config
```

---

## 🤝 Contributing

### Development Workflow
1. Fork the repository
2. Create feature branch (`git checkout -b feature/amazing-feature`)
3. Commit changes (`git commit -m 'Add amazing feature'`)
4. Push to branch (`git push origin feature/amazing-feature`)
5. Open Pull Request

### Code Standards
- **Backend**: Follow Spring Boot best practices
- **Frontend**: Use React Hooks and functional components
- **Testing**: Write unit tests for critical business logic
- **Documentation**: Update README for new features

### Issue Reporting
- Use GitHub Issues for bug reports and feature requests
- Include detailed steps to reproduce bugs
- Provide environment information and error logs

---

## 📈 Future Roadmap

### Phase 1 (Current - MVP)
- ✅ Core interview functionality
- ✅ Basic analytics
- ✅ Payment integration
- ✅ Admin management

### Phase 2 (MBP - 3 months)
- 🎨 Enhanced UI/UX
- 📊 Advanced analytics
- 🎤 Improved voice experience
- 🔧 Admin automation

### Phase 3 (Scale - 6 months)
- 👥 Team accounts
- 📝 Custom question sets
- 🎥 Interview recordings
- 🌐 Multi-language support

### Phase 4 (Enterprise - 12 months)
- 🏢 Organization features
- 📈 Advanced reporting
- 🤖 Custom AI models
- 🔗 API integrations

---

## 📞 Support & Contact

- **Email**: support@assessarc.app
- **Website**: https://assessarc.app
- **Documentation**: https://docs.assessarc.app
- **Community**: [Discord/Telegram group]

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

*Built with ❤️ for Java developers worldwide*

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
| 30 min | 10 credits | ₹10 |
| 60 min | 15 credits | ₹15 |

Credit Packs (via Razorpay):
| Pack | Price |
|------|-------|
| 10 credits | ₹10 |
| 35 credits | ₹29 |
| 70 credits | ₹59 |
| 115 credits | ₹99 |
| 220 credits | ₹199 |

Signup bonus: **10 credits** (1 free 30-minute interview)

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
java -jar target/assessarc-backend-1.0.0.jar

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
