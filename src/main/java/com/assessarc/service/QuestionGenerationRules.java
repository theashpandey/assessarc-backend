package com.assessarc.service;

import org.springframework.stereotype.Service;

@Service
public class QuestionGenerationRules {

  public String getCodingInstructions(int durationMinutes) {
    String codingInstructions = "";

    if (durationMinutes == 30) {

      codingInstructions = "Include exactly 1 CODING question (difficulty: easy). "
          + "Make it a real, trending most-asked coding question asked in top tech company(like Tata Consultancy Services (TCS), Infosys, Wipro, Accenture, Cognizant etc) interviews — not a trivial print statement.";
    } else if (durationMinutes == 60) {

      codingInstructions = "Include trending most-asked exactly 2 CODING questions. "
          + "First: easy — a common interview coding question. "
          + "Second: medium — a more realistic problem that tests algorithmic thinking. "
          + "Both should reflect trending questions asked at companies like Tata Consultancy Services (TCS), Infosys, Wipro, Accenture, Cognizant etc.";
    }
    return codingInstructions;
  }

  public String buildInterviewerSystemPrompt(String roleLabel, boolean fresher) {
    if (fresher) {
      return "You are Sarah, a friendly and experienced " + roleLabel
          + " interviewer at a top tech company like Tata Consultancy Services (TCS), Infosys, Wipro, Accenture, Cognizant etc. "
          + "You are conducting a campus or entry-level interview. "
          + "Your style is warm, patient, and encouraging — you want to understand how the candidate THINKS, not just what they know. "
          + "Your generated question set covers bookish/conceptual fundamentals, definition checks, concept clarity, small tricky checks, simple scenarios, project discussion, and beginner problem solving. "
          + "You ask questions that test curiosity, fundamentals, and potential — including trending questions asked at top companies. "
          + "You avoid jargon that requires years of production experience. "
          + "You sound like a human colleague, not a textbook. Never sound robotic or list-like. "
          + "You genuinely want to help freshers show their best.";
    } else {
      return "You are Sarah, a sharp and experienced " + roleLabel
          + " interviewer at a top tech company like Tata Consultancy Services (TCS), Infosys, Wipro, Accenture, Cognizant etc. "
          + "You conduct senior-level interviews. "
          + "Your style is direct, professional, and intellectually curious — you probe for depth, tradeoffs, and real judgment. "
          + "Your generated question set covers crisp conceptual checks, deep fundamentals, tricky edge cases, scenario tradeoffs, resume/project ownership, and behavioral judgment. "
          + "You include trending, most-asked questions from companies like Tata Consultancy Services (TCS), Infosys, Wipro, Accenture, Cognizant etc. "
          + "You are never generic. You may ask definition/comparison questions, but you do not ask lazy list-only questions. "
          + "You sound like a smart human peer who has seen a lot of systems and candidates.";
    }
  }

  public String buildDepthInstructions(String role, boolean fresher, String expLabel, int textCount,
      int fundamentalsCount, int trickyCount, int scenarioCount, int projectCount, int behavioralCount) {
    String roleExamples = getRoleBucketExamples(role, fresher);

    if (fresher) {
      return String.format(
          """
              This candidate is a FRESHER or very early in their career.
              They have limited or no real production experience — only college projects, coursework, or self-learning.
              DO NOT ask about production systems, team leadership, scaling decisions, or things requiring years of experience.

              You MUST generate questions spread across these 5 buckets (distribution below):

              BUCKET 1 — FUNDAMENTALS (%d questions):
              Test raw conceptual and bookish knowledge. These are allowed to be direct real-interview questions.
              Ask "what is X", "how does X actually work", "what's the difference between X and Y", "what happens when".
              Include multiple questions that check concept understanding without needing a resume/project example.
              These must be trending fundamentals asked at top tech companies for this role.

              BUCKET 2 — TRICKY / GOTCHA (%d questions):
              Deceptively simple questions that reveal whether they truly understand, or just memorized.
              Common interview trap questions for this role and tech stack.
              These should still be answerable by a fresher who knows the basics well.

              BUCKET 3 — SIMPLE SCENARIO / IMAGINE-YOU-ARE-BUILDING (%d questions):
              Small hypothetical scenarios. College-project-level scope. No production or scaling.
              "Imagine you're building...", "If you had to design a small...", "What would you do if..."

              BUCKET 4 — PROJECT / EXPERIENCE BASED (%d questions):
              Ask about their own projects (college projects are fine). Probe technical depth.
              Reference actual skills/projects from their resume naturally.

              BUCKET 5 — BEHAVIORAL / CURIOSITY (%d questions):
              Ask how they think, how they learn, and how they handle challenges.
              How they think, how they learn, how they handle challenges.
              "When you get stuck...", "Is there something in [tech] you tried recently..."

              Role-specific example questions to guide your tone and style (DO NOT copy verbatim — use as reference only):
              %s

              STRICT RULES:
              - Avoid "in your current role", "in production", "your team", "scaling to millions"
              - Vary openers across all questions — no two questions should start the same way
              - Warm and encouraging tone — campus-style interview feel
              - Freshers should get mostly fundamentals, bookish concepts, tricky basics, and project explanation; only a few scenarios
              - Do not make all fresher questions resume/project/scenario based
              - At least 50 percent of fresher TEXT questions must be standalone conceptual or tricky conceptual questions
              - Mix the buckets in the output array (do not cluster them)
              - Include trending questions actually asked in tech company interviews for this role
              """,
          fundamentalsCount, trickyCount, scenarioCount, projectCount, behavioralCount, roleExamples);
    } else {
      return String.format("""
          This is an EXPERIENCED candidate (%s experience).
          Ask questions that test real depth, tradeoffs, ownership, and battle-tested judgment.

          You MUST generate questions spread across these 5 buckets (distribution below):

          BUCKET 1 — DEEP FUNDAMENTALS / INTERNALS (%d questions):
          Not textbook definitions — test real depth and internals. "How does X actually work under the hood?"
          Include trending deep-dive questions asked at top tech companies for this role.

          BUCKET 2 — TRICKY / GOTCHA (%d questions):
          Questions even experienced engineers get wrong or oversimplify.
          Common interview traps and edge cases for this role and tech stack.

          BUCKET 3 — DESIGN / SCENARIO / TRADEOFF (%d questions):
          Real engineering decisions. Force them to justify choices.
          "How would you design...", "What tradeoff would you make...", "When would you choose X over Y?"
          Reflect trending system design and scenario questions from FAANG-style interviews.

          BUCKET 4 — RESUME / PROJECT DEPTH (%d questions):
          Dig into their actual work — reference real skills and projects from their resume.
          Ask follow-ups that expose whether they own it or just participated.
          "Walk me through a time you...", "What would you do differently..."

          BUCKET 5 — BEHAVIORAL / LEADERSHIP (%d questions):
          Test judgment, conflict resolution, and engineering maturity.
          Test judgment, conflict resolution, and engineering maturity.
          "Tell me about a time...", "How do you decide...", "Your team disagrees..."

          Role-specific example questions to guide your tone and style (DO NOT copy verbatim — use as reference only):
          %s

          STRICT RULES:
          - Probe for OWNERSHIP not just participation ("you" not just "your team")
          - Never ask generic definitions — test application and real judgment
          - Vary openers radically across all questions
          - Mix the buckets in the output array (do not cluster them)
          - Include trending questions actually asked in real interviews at top tech companies for this role
          """, expLabel, fundamentalsCount, trickyCount, scenarioCount, projectCount, behavioralCount, roleExamples);
    }
  }

  public String getConceptDrillExamples(String role, boolean fresher) {
    return switch (role) {
      case "java_developer" -> """
          CONCEPT DRILLS: "What is @RestController in Spring Boot?", "What is the difference between fail-fast and fail-safe iterators?", "What is the difference between HashMap and ConcurrentHashMap?", "What is polymorphism in Java?"
          """;
      case "python_developer" -> """
          CONCEPT DRILLS: "What is the difference between list, tuple, set, and dictionary?", "What is a decorator in Python?", "What is the GIL?", "What is the difference between shallow copy and deep copy?"
          """;
      case "dotnet_developer", "csharp_developer" -> """
          CONCEPT DRILLS: "What is dependency injection in ASP.NET Core?", "What is the difference between struct and class in C#?", "What is async/await?", "What is middleware in .NET?"
          """;
      case "nodejs_developer" -> """
          CONCEPT DRILLS: "What is the event loop in Node.js?", "What is middleware in Express?", "What is the difference between require and import?", "What is callback hell?"
          """;
      case "react_developer" -> """
          CONCEPT DRILLS: "What is the virtual DOM?", "What is the difference between props and state?", "What is useEffect used for?", "What is controlled vs uncontrolled component?"
          """;
      case "angular_developer" -> """
          CONCEPT DRILLS: "What is dependency injection in Angular?", "What is the difference between component and directive?", "What is RxJS Observable?", "What is lazy loading?"
          """;
      case "frontend_engineer" -> """
          CONCEPT DRILLS: "What is event bubbling?", "What is the difference between localStorage and sessionStorage?", "What is the CSS box model?", "What is CORS?"
          """;
      case "backend_engineer" -> """
          CONCEPT DRILLS: "What is REST?", "What is idempotency?", "What is the difference between authentication and authorization?", "What is database indexing?"
          """;
      case "full_stack_developer" -> """
          CONCEPT DRILLS: "What happens from clicking a button in the UI to saving data in the database?", "What is REST API?", "What is JWT?", "What is the difference between client-side and server-side rendering?"
          """;
      case "software_engineer" -> """
          CONCEPT DRILLS: "What is OOP?", "What is the difference between stack and heap?", "What is time complexity?", "What is the difference between interface and abstract class?"
          """;
      case "data_analyst" -> """
          CONCEPT DRILLS: "What is the difference between INNER JOIN and LEFT JOIN?", "What is a KPI?", "What is correlation vs causation?", "What is a window function?"
          """;
      case "sql_developer" -> """
          CONCEPT DRILLS: "What is normalization?", "What is an index?", "What is the difference between WHERE and HAVING?", "What is a primary key vs foreign key?"
          """;
      case "data_scientist" -> """
          CONCEPT DRILLS: "What is overfitting?", "What is p-value?", "What is precision vs recall?", "What is the bias-variance tradeoff?"
          """;
      case "data_engineer" -> """
          CONCEPT DRILLS: "What is ETL vs ELT?", "What is partitioning?", "What is batch vs streaming?", "What is data warehouse vs data lake?"
          """;
      case "ai_engineer" -> """
          CONCEPT DRILLS: "What is model evaluation?", "What is embedding?", "What is inference?", "What is the difference between rule-based logic and AI-based prediction?"
          """;
      case "generative_ai_engineer" -> """
          CONCEPT DRILLS: "What is RAG?", "What is tokenization?", "What is temperature in LLM output?", "What is prompt injection?"
          """;
      case "machine_learning_engineer" -> """
          CONCEPT DRILLS: "What is train-validation-test split?", "What is feature engineering?", "What is model drift?", "What is cross-validation?"
          """;
      case "prompt_engineer" -> """
          CONCEPT DRILLS: "What is few-shot prompting?", "What is system prompt vs user prompt?", "What is hallucination?", "What is prompt chaining?"
          """;
      case "devops_engineer" -> """
          CONCEPT DRILLS: "What is CI/CD?", "What is Docker image vs container?", "What is Kubernetes pod?", "What is infrastructure as code?"
          """;
      case "cloud_engineer", "aws_engineer", "azure_engineer" -> """
          CONCEPT DRILLS: "What is VPC?", "What is IAM?", "What is load balancing?", "What is horizontal vs vertical scaling?"
          """;
      case "cybersecurity_analyst" -> """
          CONCEPT DRILLS: "What is phishing?", "What is encryption vs hashing?", "What is least privilege?", "What is vulnerability vs threat?"
          """;
      case "qa_automation_engineer", "sdet" -> """
          CONCEPT DRILLS: "What is regression testing?", "What is smoke vs sanity testing?", "What is Selenium WebDriver?", "What is flaky test?"
          """;
      case "mobile_developer" -> """
          CONCEPT DRILLS: "What is activity lifecycle?", "What is main thread?", "What is local storage in mobile apps?", "What is push notification?"
          """;
      case "software_architect" -> """
          CONCEPT DRILLS: "What is monolith vs microservices?", "What is CAP theorem?", "What is separation of concerns?", "What is event-driven architecture?"
          """;
      case "engineering_manager" -> """
          CONCEPT DRILLS: "What is sprint planning?", "What is one-on-one?", "What is psychological safety?", "What is performance feedback?"
          """;
      case "product_manager" -> """
          CONCEPT DRILLS: "What is MVP?", "What is roadmap?", "What is north-star metric?", "What is product-market fit?"
          """;
      case "business_analyst" -> """
          CONCEPT DRILLS: "What is BRD vs FRD?", "What is user story?", "What is acceptance criteria?", "What is stakeholder analysis?"
          """;
      case "ui_ux_designer" -> """
          CONCEPT DRILLS: "What is user persona?", "What is wireframe vs prototype?", "What is design system?", "What is usability testing?"
          """;
      case "hr_recruiter" -> """
          CONCEPT DRILLS: "What is sourcing?", "What is screening?", "What is offer negotiation?", "What is candidate experience?"
          """;
      default -> fresher
          ? """
              CONCEPT DRILLS: "What is one core concept in this role?", "What is the difference between two common tools in this role?", "What is one basic term every fresher should know?", "What is a common beginner mistake?"
              """
          : """
              CONCEPT DRILLS: "What is one core concept people often misunderstand in this role?", "What tradeoff hides behind a common term in this role?", "What is a senior-level gotcha in this domain?"
              """;
    };
  }

  private String getRoleBucketExamples(String role, boolean fresher) {
    return switch (role) {

    case "ai_engineer" -> fresher
        ? """
            FUNDAMENTALS: "How would you explain the difference between traditional software logic and an AI-powered feature?"
            FUNDAMENTALS: "What does model evaluation mean, and why is accuracy alone often not enough?"
            TRICKY: "If an AI feature works well in a demo but fails for real users, what are the first causes you would check?"
            SCENARIO: "Imagine you're adding an AI assistant to a support app — what would you validate before shipping it?"
            PROJECT: "Walk me through an AI project you tried — what data, model, or API did you use and why?"
            BEHAVIORAL: "When an AI answer looks confident but suspicious, how do you decide whether to trust it?"
            """
        : """
            FUNDAMENTALS: "How do you decide between using a hosted model API, fine-tuning, or building a classical ML model?"
            FUNDAMENTALS: "What metrics and guardrails would you use before allowing an AI workflow into production?"
            TRICKY: "A model's offline evaluation improves but product outcomes get worse — how do you investigate that gap?"
            DESIGN: "How would you design an AI service that is observable, cost-controlled, and resilient to provider failures?"
            PROJECT: "Tell me about an AI system you shipped or owned — what was the hardest production tradeoff?"
            BEHAVIORAL: "Tell me about a time you had to push back on an unrealistic AI requirement."
            """;

    case "generative_ai_engineer" -> fresher ? """
        FUNDAMENTALS: "What is the difference between prompting, RAG, and fine-tuning in a GenAI application?"
        FUNDAMENTALS: "Why can LLMs hallucinate, and what practical steps reduce that risk?"
        TRICKY: "If two similar prompts produce very different answers, what might be happening?"
        SCENARIO: "Imagine you're building a resume Q&A bot — how would you ground answers in the uploaded document?"
        PROJECT: "Walk me through a GenAI demo or project you built — what prompt or retrieval choices mattered most?"
        BEHAVIORAL: "How do you test an LLM feature when outputs are not exactly the same every time?"
        """
        : """
            FUNDAMENTALS: "How do chunking strategy, embeddings, and retrieval ranking affect the quality of a RAG system?"
            FUNDAMENTALS: "How would you evaluate faithfulness, relevance, latency, and cost for an LLM workflow?"
            TRICKY: "Your RAG system returns the right document but the answer is still wrong — where do you look next?"
            DESIGN: "How would you design a multi-tenant enterprise GenAI assistant with access control and auditability?"
            PROJECT: "Tell me about a GenAI failure you debugged — was the issue prompt, retrieval, model, or product design?"
            BEHAVIORAL: "How do you explain GenAI limitations to stakeholders who expect deterministic software?"
            """;

    case "machine_learning_engineer" -> fresher
        ? """
            FUNDAMENTALS: "What is the difference between training, validation, and test data — why do all three matter?"
            FUNDAMENTALS: "How would you explain precision and recall using a fraud detection example?"
            TRICKY: "If your model gets 99% accuracy on an imbalanced dataset, why might that be a bad sign?"
            SCENARIO: "You trained a model in a notebook — what steps turn it into something an application can use?"
            PROJECT: "Walk me through an ML project you built — what feature engineering or evaluation decision mattered most?"
            BEHAVIORAL: "When a model performs poorly, how do you decide whether to improve data, features, or algorithm?"
            """
        : """
            FUNDAMENTALS: "How do you design an ML training and serving pipeline that prevents training-serving skew?"
            FUNDAMENTALS: "What drift signals do you monitor after a model goes live, and what action do they trigger?"
            TRICKY: "A model performs well in batch validation but poorly for low-latency online requests — what could be wrong?"
            DESIGN: "How would you design a feature store and deployment process for multiple ML models?"
            PROJECT: "Tell me about an ML system you productionized — what broke after launch and how did you fix it?"
            BEHAVIORAL: "Tell me about a time business constraints forced you to choose a simpler model."
            """;

    case "dotnet_developer", "csharp_developer" -> fresher
        ? """
            FUNDAMENTALS: "What is the difference between value types and reference types in C#?"
            FUNDAMENTALS: "How does async and await work in C# at a practical level?"
            TRICKY: "What can go wrong if you block on an async call using .Result or .Wait()?"
            SCENARIO: "Imagine you're building a simple Web API in .NET — how would you structure controllers, services, and data access?"
            PROJECT: "Tell me about a C# or .NET project you built — what library or framework choice mattered?"
            BEHAVIORAL: "When you hit a runtime exception in .NET, what's your debugging process?"
            """
        : """
            FUNDAMENTALS: "How does dependency injection work in ASP.NET Core, and what lifetime mistakes cause production bugs?"
            FUNDAMENTALS: "How do async streams, tasks, and thread-pool behavior affect throughput in a .NET API?"
            TRICKY: "When can Entity Framework generate inefficient SQL, and how do you catch it before it hurts production?"
            DESIGN: "How would you design a .NET service that handles high write volume while keeping APIs responsive?"
            PROJECT: "Walk me through a production issue you debugged in a .NET application."
            BEHAVIORAL: "Tell me about a time you refactored C# code without breaking existing behavior."
            """;

    case "nodejs_developer" -> fresher ? """
        FUNDAMENTALS: "What is the Node.js event loop, and why does it matter for backend code?"
        FUNDAMENTALS: "What's the difference between callbacks, promises, and async/await?"
        TRICKY: "If one request runs a CPU-heavy loop in Node.js, what happens to other requests?"
        SCENARIO: "You're building a REST API with Express — how would you handle validation and errors?"
        PROJECT: "Walk me through a Node.js project you built — how did you structure routes and services?"
        BEHAVIORAL: "When an npm package looks useful, how do you decide if it's safe to add?"
        """ : """
        FUNDAMENTALS: "How do you diagnose event-loop blocking in a Node.js production service?"
        FUNDAMENTALS: "How do streams and backpressure work in Node.js?"
        TRICKY: "A memory leak only appears after hours of traffic — how do you investigate it in Node?"
        DESIGN: "How would you design a Node.js API that handles bursts, retries, and downstream failures?"
        PROJECT: "Tell me about a Node service you owned — what scaling or reliability issue did you solve?"
        BEHAVIORAL: "Tell me about a time dependency risk changed your implementation plan."
        """;

    case "angular_developer" -> fresher ? """
        FUNDAMENTALS: "What is the difference between components, services, and modules in Angular?"
        FUNDAMENTALS: "How does two-way binding work, and when can it become messy?"
        TRICKY: "What can go wrong if you subscribe to observables and never unsubscribe?"
        SCENARIO: "Imagine you're building a form-heavy dashboard in Angular — how would you approach validation?"
        PROJECT: "Walk me through an Angular project you built — how did you manage state and API calls?"
        BEHAVIORAL: "What Angular concept took you the longest to understand?"
        """
        : """
            FUNDAMENTALS: "How does Angular change detection work, and when would you use OnPush?"
            FUNDAMENTALS: "How do RxJS operators help manage complex async workflows?"
            TRICKY: "A component keeps re-rendering or firing API calls unexpectedly — how do you debug it?"
            DESIGN: "How would you architect a large Angular application with shared modules, lazy loading, and permissions?"
            PROJECT: "Tell me about a performance issue you solved in Angular."
            BEHAVIORAL: "Tell me about a time frontend architecture decisions affected team velocity."
            """;

    case "data_analyst" -> fresher ? """
        FUNDAMENTALS: "How would you explain joins in SQL using a simple business example?"
        FUNDAMENTALS: "What's the difference between a metric, a dimension, and a KPI?"
        TRICKY: "If two dashboards show different revenue numbers, how do you investigate which one is right?"
        SCENARIO: "A product manager asks why signups dropped last week — how would you start the analysis?"
        PROJECT: "Walk me through an analysis or dashboard you built — what insight did it reveal?"
        BEHAVIORAL: "How do you communicate uncertainty when the data is incomplete?"
        """ : """
        FUNDAMENTALS: "How do you design a metric so it stays consistent across teams and dashboards?"
        FUNDAMENTALS: "How do window functions change the way you solve analytical SQL problems?"
        TRICKY: "A query result is technically correct but leads stakeholders to a wrong conclusion — what happened?"
        DESIGN: "How would you build an executive dashboard that is trusted, explainable, and hard to misuse?"
        PROJECT: "Tell me about a time your analysis changed a business decision."
        BEHAVIORAL: "Tell me about a time you pushed back on a stakeholder's interpretation of data."
        """;

    case "sql_developer" -> fresher ? """
        FUNDAMENTALS: "What's the difference between INNER JOIN, LEFT JOIN, and FULL OUTER JOIN?"
        FUNDAMENTALS: "What is an index, and why can it make one query faster but writes slower?"
        TRICKY: "Why can COUNT(*) and COUNT(column_name) return different numbers?"
        SCENARIO: "You need to find duplicate customer records in a table — how would you write that query?"
        PROJECT: "Tell me about a database or reporting project you worked on — what tables mattered most?"
        BEHAVIORAL: "When a SQL query returns unexpected results, how do you debug it?"
        """ : """
        FUNDAMENTALS: "How do you read an execution plan and decide where the query is actually slow?"
        FUNDAMENTALS: "When would you use a window function instead of GROUP BY?"
        TRICKY: "An index exists but the database still does a full scan — what are the possible reasons?"
        DESIGN: "How would you design tables and stored procedures for a reporting workload with heavy reads?"
        PROJECT: "Tell me about the hardest query optimization problem you've solved."
        BEHAVIORAL: "Tell me about a time a database change had to be made with zero downtime."
        """;

    case "business_analyst" -> fresher ? """
        FUNDAMENTALS: "How do you turn a vague business request into clear requirements?"
        FUNDAMENTALS: "What's the difference between functional and non-functional requirements?"
        TRICKY: "A stakeholder asks for a feature, but the problem is unclear — what do you do?"
        SCENARIO: "Imagine a checkout process has high drop-off — how would you gather and structure requirements?"
        PROJECT: "Walk me through a project where you had to understand both business and technical constraints."
        BEHAVIORAL: "How do you handle two stakeholders who want conflicting things?"
        """ : """
        FUNDAMENTALS: "How do you validate requirements before engineering starts implementation?"
        FUNDAMENTALS: "How do process maps, user stories, and acceptance criteria work together?"
        TRICKY: "A requirement is signed off but later turns out to solve the wrong problem — how do you recover?"
        DESIGN: "How would you redesign a requirements intake process for a fast-moving product team?"
        PROJECT: "Tell me about a time your analysis prevented wasted engineering effort."
        BEHAVIORAL: "Tell me about a time you had to say no to a senior stakeholder."
        """;

    case "aws_engineer", "azure_engineer" -> fresher ? """
        FUNDAMENTALS: "What is a virtual network or VPC, and why is it central to cloud architecture?"
        FUNDAMENTALS: "How do IAM roles and permissions protect cloud resources?"
        TRICKY: "If an application cannot connect to a database in the cloud, what layers do you check first?"
        SCENARIO: "You need to host a small web app securely in the cloud — what services would you choose?"
        PROJECT: "Walk me through anything you've deployed on AWS or Azure — what did you configure yourself?"
        BEHAVIORAL: "How do you learn a cloud service you have not used before?"
        """ : """
        FUNDAMENTALS: "How do identity, networking, and logging choices affect cloud security posture?"
        FUNDAMENTALS: "How do you design for high availability across zones or regions without wasting cost?"
        TRICKY: "A workload is reliable but the cloud bill doubles — how do you find the cause?"
        DESIGN: "How would you design a secure landing zone for multiple teams and environments?"
        PROJECT: "Tell me about a cloud migration or infrastructure build you owned."
        BEHAVIORAL: "Tell me about a time reliability and cost goals were in tension."
        """;

    case "cybersecurity_analyst" -> fresher ? """
        FUNDAMENTALS: "What's the difference between vulnerability, threat, and risk?"
        FUNDAMENTALS: "How would you explain phishing detection to a non-technical employee?"
        TRICKY: "If an alert fires repeatedly but no incident is found, do you ignore it or tune it?"
        SCENARIO: "A user's account shows suspicious login activity — what steps do you take first?"
        PROJECT: "Walk me through any security lab, CTF, or monitoring project you have done."
        BEHAVIORAL: "How do you stay calm and structured when something looks like an incident?"
        """ : """
        FUNDAMENTALS: "How do you triage alerts so real incidents are not buried under noise?"
        FUNDAMENTALS: "How do MITRE ATT&CK, logs, and endpoint telemetry help your investigation?"
        TRICKY: "A vulnerability is critical but exploitation seems unlikely — how do you prioritize it?"
        DESIGN: "How would you design an incident response workflow from detection to postmortem?"
        PROJECT: "Tell me about a real or simulated incident you investigated — what evidence mattered most?"
        BEHAVIORAL: "Tell me about a time you had to communicate security risk to leadership."
        """;

    case "sdet" -> fresher ? """
        FUNDAMENTALS: "What's the difference between testing a feature manually and building automated coverage for it?"
        FUNDAMENTALS: "How do you choose test data for positive, negative, and edge cases?"
        TRICKY: "Can a passing automated test still hide a serious bug?"
        SCENARIO: "You're asked to automate tests for an API endpoint — what cases would you cover first?"
        PROJECT: "Walk me through a test automation project you built or contributed to."
        BEHAVIORAL: "How do you explain a bug so developers can reproduce it quickly?"
        """ : """
        FUNDAMENTALS: "How do you design an automation framework that is maintainable across teams?"
        FUNDAMENTALS: "How do contract tests, integration tests, and end-to-end tests complement each other?"
        TRICKY: "A flaky test blocks releases but catches real bugs sometimes — how do you handle it?"
        DESIGN: "How would you build CI quality gates without making the pipeline painfully slow?"
        PROJECT: "Tell me about an automation strategy that improved release confidence."
        BEHAVIORAL: "Tell me about a time you challenged a release because of quality risk."
        """;

    case "prompt_engineer" -> fresher ? """
        FUNDAMENTALS: "What makes a prompt specific enough for useful LLM output?"
        FUNDAMENTALS: "How do examples in a prompt change the model's behavior?"
        TRICKY: "If a prompt works once but fails on a similar input, what do you change first?"
        SCENARIO: "You need an LLM to summarize support tickets consistently — how would you design the prompt?"
        PROJECT: "Walk me through a prompt workflow you built — how did you test quality?"
        BEHAVIORAL: "How do you handle feedback when users say the AI output feels wrong but cannot explain why?"
        """ : """
        FUNDAMENTALS: "How do you evaluate prompt quality across accuracy, safety, consistency, and latency?"
        FUNDAMENTALS: "When would you use prompt templates, tool calls, retrieval, or fine-tuning?"
        TRICKY: "A model follows the prompt in English but fails with multilingual input — how do you debug it?"
        DESIGN: "How would you build a prompt management and regression testing process for production LLM features?"
        PROJECT: "Tell me about a prompt system you improved measurably."
        BEHAVIORAL: "Tell me about a time you had to align creative output with strict business constraints."
        """;

    case "ui_ux_designer" -> fresher ? """
        FUNDAMENTALS: "How do you explain the difference between UI and UX?"
        FUNDAMENTALS: "What makes a user flow easy to understand?"
        TRICKY: "If users say a design looks good but cannot complete the task, what does that tell you?"
        SCENARIO: "Imagine you're redesigning a signup form with high drop-off — how would you approach it?"
        PROJECT: "Walk me through a design project — what problem were you solving and how did the design change?"
        BEHAVIORAL: "How do you respond when a stakeholder wants a design change you think hurts usability?"
        """ : """
        FUNDAMENTALS: "How do you connect research insights to concrete interaction and visual design decisions?"
        FUNDAMENTALS: "How do design systems improve consistency without limiting product flexibility?"
        TRICKY: "A usability test contradicts stakeholder preferences — how do you handle the decision?"
        DESIGN: "How would you redesign a complex workflow for expert users without slowing them down?"
        PROJECT: "Tell me about a design decision that improved a measurable product outcome."
        BEHAVIORAL: "Tell me about a time engineering constraints forced you to rethink a design."
        """;

    case "java_developer" -> fresher
        ? """
            FUNDAMENTALS: "What actually happens in memory when you create an object in Java — walk me through it."
            FUNDAMENTALS: "Can you explain the difference between == and .equals() in Java, and when would == fool you?"
            FUNDAMENTALS: "How does HashMap handle two keys that produce the same hash code?"
            FUNDAMENTALS: "What's the difference between checked and unchecked exceptions — when would you use each?"
            TRICKY: "Can you have a try block without a catch block in Java? What would that look like?"
            TRICKY: "Is String a primitive in Java? What makes it behave differently from int?"
            SCENARIO: "Imagine you're building a simple student management REST API — how would you structure the endpoints?"
            SCENARIO: "You have a list of 1000 user objects and need to find all users above age 25 — how would you do it in Java 8+?"
            PROJECT: "Tell me about a Java project you built — what was the hardest bug you ran into?"
            BEHAVIORAL: "When you get stuck on a Java problem, what's your actual process for figuring it out?"
            """
        : """
            FUNDAMENTALS: "How does the JVM decide when to promote an object from young gen to old gen — and what have you seen go wrong with that?"
            FUNDAMENTALS: "How does Spring's @Transactional actually work — what happens if you call a @Transactional method from within the same class?"
            FUNDAMENTALS: "Walk me through what happens to a request between hitting your load balancer and a DB query executing."
            TRICKY: "If you use @Cacheable in Spring and two threads request the same uncached key simultaneously, what happens?"
            TRICKY: "Can a transaction span two microservices? What actually happens if you try?"
            TRICKY: "If you set a Kafka consumer group with 10 partitions and 12 consumers, what happens to the extra two?"
            DESIGN: "How would you design a rate limiter for an API handling 50,000 requests per second?"
            DESIGN: "When would you choose an event-driven architecture over a synchronous REST call — what's your decision framework?"
            PROJECT: "Walk me through a production issue you debugged in a Spring Boot app — what was your investigation process?"
            BEHAVIORAL: "Tell me about a time you disagreed with your tech lead's architecture decision — what did you do?"
            """;

    case "python_developer" -> fresher
        ? """
            FUNDAMENTALS: "What's the difference between a list and a tuple in Python — not just that one is mutable, but when would you actually choose one over the other?"
            FUNDAMENTALS: "What does a decorator actually do under the hood — can you walk me through it step by step?"
            FUNDAMENTALS: "How does Python handle memory management — what's reference counting and when does it not work?"
            TRICKY: "What happens if you use a mutable default argument in a Python function — can you show me an example of why that's dangerous?"
            TRICKY: "Is everything in Python an object? What does that actually mean practically?"
            SCENARIO: "Imagine you're building a REST API with FastAPI — how would you handle validation of incoming request data?"
            SCENARIO: "You need to read a CSV file with 100,000 rows — what's the memory-efficient way to do it in Python?"
            PROJECT: "Tell me about a Python project you built — what libraries did you use and why?"
            BEHAVIORAL: "Is there something in Python you tried recently that surprised you or confused you at first?"
            """
        : """
            FUNDAMENTALS: "How does Python's GIL actually work — when does it matter and when doesn't it?"
            FUNDAMENTALS: "What's the difference between multiprocessing and threading in Python — give me a scenario where you'd pick each one."
            TRICKY: "If you have a generator and you iterate it twice, what happens the second time — and why?"
            TRICKY: "What's the difference between __str__ and __repr__ — when does each get called automatically?"
            DESIGN: "How would you design a background job system in Python that's reliable even if workers crash mid-job?"
            DESIGN: "Your Django app is getting slow under load — walk me through how you'd diagnose and fix it."
            PROJECT: "Tell me about a Python service you owned in production — what was the most painful scaling issue you hit?"
            BEHAVIORAL: "Tell me about a time you had to choose between writing clean Python code and shipping fast — what did you decide?"
            """;

    case "react_developer" -> fresher
        ? """
            FUNDAMENTALS: "What's the difference between state and props in React — when would changing one re-render a component and not the other?"
            FUNDAMENTALS: "What does useEffect actually do — what happens if you forget the dependency array?"
            FUNDAMENTALS: "What is the virtual DOM and why does React use it instead of updating the real DOM directly?"
            TRICKY: "If you call setState twice in a row inside the same function, does the component re-render twice?"
            TRICKY: "Can you use a hook inside an if statement? Why or why not?"
            SCENARIO: "Imagine you're building a search bar that calls an API on every keystroke — what problem does that create and how do you fix it?"
            SCENARIO: "You need to share data between two sibling components — how would you approach that?"
            PROJECT: "Walk me through a React project you built — how did you manage state?"
            BEHAVIORAL: "What's something about React that confused you at first but now makes sense?"
            """
        : """
            FUNDAMENTALS: "How does React's reconciliation algorithm decide what to re-render — and how does the key prop affect that?"
            FUNDAMENTALS: "What's the difference between useMemo and useCallback — give me a real case where each one actually helps performance?"
            TRICKY: "When does a useEffect cleanup function run exactly — give me a case where forgetting it causes a real bug."
            TRICKY: "Why can stale closures in useEffect be a problem — how do you reproduce and fix one?"
            DESIGN: "How would you architect state management for a large React app — when does Redux make sense vs Context API vs Zustand?"
            DESIGN: "Your React app is slow on initial load — walk me through your optimization process."
            PROJECT: "Tell me about a performance problem you hit in a React app and how you diagnosed it."
            BEHAVIORAL: "Tell me about a time a frontend bug was much harder to debug than you expected — what made it hard?"
            """;

    case "full_stack_developer" -> fresher
        ? """
            FUNDAMENTALS: "Walk me through what happens from the moment a user types a URL in the browser to when the page loads."
            FUNDAMENTALS: "What's the difference between a REST API and GraphQL — when would you use one over the other?"
            FUNDAMENTALS: "What is CORS and why does it exist — have you run into a CORS issue in a project?"
            TRICKY: "If your frontend sends a POST request but the server returns a 200 with an error message in the body, how does your frontend know something went wrong?"
            SCENARIO: "You're building a simple e-commerce app — how would you split responsibilities between the frontend and backend?"
            SCENARIO: "Your API is slow and your React app feels sluggish — where do you start debugging?"
            PROJECT: "Tell me about a full-stack project you built — what was the trickiest integration between frontend and backend?"
            BEHAVIORAL: "What part of full-stack development do you find more interesting — frontend or backend — and why?"
            """
        : """
            FUNDAMENTALS: "How does session management work in a stateless REST API — walk me through the full flow with JWTs."
            FUNDAMENTALS: "What's the N+1 query problem and how do you catch and fix it in a full-stack app?"
            TRICKY: "You have a React frontend calling a Node/Java backend — where exactly does an auth token get validated, and what happens if the token expires mid-session?"
            DESIGN: "How would you design the architecture for a real-time collaborative document editor — like Google Docs?"
            DESIGN: "Your full-stack app needs to handle file uploads up to 500MB — how do you design that end-to-end?"
            PROJECT: "Walk me through a full-stack feature you owned from design to deployment — what would you do differently?"
            BEHAVIORAL: "Tell me about a time a frontend change broke something in the backend unexpectedly — how did you handle it?"
            """;

    case "backend_engineer" -> fresher ? """
        FUNDAMENTALS: "What's the difference between SQL and NoSQL databases — when would you pick one over the other?"
        FUNDAMENTALS: "What does it mean for an API to be RESTful — can you walk me through the key principles?"
        FUNDAMENTALS: "What is an index in a database and why does it make queries faster?"
        TRICKY: "What happens if two users try to update the same database row at exactly the same time?"
        SCENARIO: "You're building a URL shortener service — how would you design the backend?"
        SCENARIO: "Your API endpoint is returning data slowly — where do you start looking?"
        PROJECT: "Tell me about a backend project you built — how did you handle data storage?"
        BEHAVIORAL: "What's something about backend development that surprised you when you first started learning it?"
        """
        : """
            FUNDAMENTALS: "How does database connection pooling work — what happens when all connections are in use?"
            FUNDAMENTALS: "Walk me through how you would implement idempotency for a payment API endpoint."
            TRICKY: "What's the difference between optimistic and pessimistic locking — give me a real scenario where optimistic locking completely fails?"
            TRICKY: "Can you have a distributed transaction across two microservices? What actually happens if one fails halfway?"
            DESIGN: "How would you design a notification service that sends email, SMS, and push — ensuring one failing channel doesn't block others?"
            DESIGN: "How do you design an API that's backward-compatible when you need to make a breaking change?"
            PROJECT: "Tell me about a production incident you dealt with on a backend service — what was your debugging process?"
            BEHAVIORAL: "How do you decide when tech debt is worth fixing now versus deferring?"
            """;

    case "frontend_engineer" -> fresher
        ? """
            FUNDAMENTALS: "What's the difference between display:none, visibility:hidden, and opacity:0 in CSS — when would you use each?"
            FUNDAMENTALS: "What does 'semantic HTML' mean and why does it matter?"
            FUNDAMENTALS: "How does the browser render a webpage — what's the critical rendering path?"
            TRICKY: "What is event bubbling in JavaScript — can you give me an example where it causes an unexpected behavior?"
            SCENARIO: "You need to build a responsive navigation menu that works on mobile and desktop — how do you approach it?"
            SCENARIO: "Your webpage is loading slowly — what are the first three things you check?"
            PROJECT: "Walk me through a frontend project you built — how did you handle responsiveness?"
            BEHAVIORAL: "What's a CSS or JS behavior that confused you at first but now you understand well?"
            """
        : """
            FUNDAMENTALS: "What is the browser's event loop — how does it relate to async/await and Promises?"
            FUNDAMENTALS: "How does CSS specificity work — walk me through a case where specificity caused a hard-to-debug styling issue."
            TRICKY: "What's the difference between debounce and throttle — when does using the wrong one cause a real problem?"
            TRICKY: "What are Web Workers and when would you actually use one?"
            DESIGN: "How would you architect a design system for a large frontend app that multiple teams contribute to?"
            DESIGN: "Your Core Web Vitals scores are poor — walk me through how you'd diagnose and improve LCP, FID, and CLS."
            PROJECT: "Tell me about a complex UI component you built — what were the edge cases you had to handle?"
            BEHAVIORAL: "Tell me about a time a cross-browser compatibility issue caused a real problem — how did you find and fix it?"
            """;

    case "software_engineer" -> fresher
        ? """
            FUNDAMENTALS: "What's the difference between a stack and a queue — when would you use each one?"
            FUNDAMENTALS: "What does time complexity mean — can you walk me through the difference between O(n) and O(n²) with a real example?"
            FUNDAMENTALS: "What is a null pointer and why is it such a common bug?"
            TRICKY: "What's the difference between pass-by-value and pass-by-reference — does Java do one or both?"
            SCENARIO: "If you had to design a parking lot management system, what objects and classes would you model?"
            SCENARIO: "You have a bug in your code but you don't know where it is — walk me through your debugging process."
            PROJECT: "Tell me about a program you wrote from scratch — what made it technically interesting?"
            BEHAVIORAL: "What's a programming concept that took you a while to really understand — how did it finally click?"
            """
        : """
            FUNDAMENTALS: "Walk me through how you approach designing a class hierarchy — what signals tell you to use inheritance vs composition?"
            FUNDAMENTALS: "What's the difference between a process and a thread — at the OS level, what resources does each one own?"
            TRICKY: "What are the SOLID principles — give me a real case where violating one caused a real maintenance problem."
            DESIGN: "How would you design a job scheduling system that can run millions of tasks reliably?"
            DESIGN: "You need to build a cache layer for a high-read service — what tradeoffs do you consider?"
            PROJECT: "Walk me through the most technically complex piece of software you've built — what made it hard?"
            BEHAVIORAL: "Tell me about a time you had to refactor a large piece of legacy code — how did you approach it safely?"
            """;

    case "data_scientist" -> fresher
        ? """
            FUNDAMENTALS: "What's the difference between supervised and unsupervised learning — give me a real example of each."
            FUNDAMENTALS: "What does overfitting mean — how would you know if your model is doing it?"
            FUNDAMENTALS: "Why do we split data into train and test sets — what goes wrong if you don't?"
            TRICKY: "If your model has 99% accuracy, is it necessarily a good model? When would that be a red flag?"
            TRICKY: "What's the difference between correlation and causation — give me an example where confusing them would be dangerous?"
            SCENARIO: "You've trained a model that performs great in testing but terribly in production — where do you start looking?"
            SCENARIO: "You have a dataset with 30% missing values — how do you decide what to do with them?"
            PROJECT: "Walk me through a data analysis or ML project you did — what was your feature engineering process?"
            BEHAVIORAL: "What's a machine learning concept that surprised you once you understood it deeply?"
            """
        : """
            FUNDAMENTALS: "How does gradient boosting actually work — what's it doing differently from a single decision tree or random forest?"
            FUNDAMENTALS: "What's the bias-variance tradeoff and how do you tune for it in practice — not just theoretically?"
            TRICKY: "When would you NOT use cross-validation — give me a specific scenario."
            TRICKY: "Can feature scaling hurt a tree-based model? Why or why not?"
            DESIGN: "How would you design an A/B test for a new recommendation algorithm — what are the ways your results could be invalid?"
            DESIGN: "Your model's performance degrades over time in production — how do you build a system to detect and respond to that?"
            PROJECT: "Tell me about a model you deployed to production — what was harder than you expected?"
            BEHAVIORAL: "Tell me about a time your model was technically correct but the business stakeholders pushed back — how did you handle it?"
            """;

    case "data_engineer" -> fresher
        ? """
            FUNDAMENTALS: "What's the difference between a data warehouse and a data lake — when would you use each?"
            FUNDAMENTALS: "What is an ETL pipeline — can you walk me through what each step does?"
            FUNDAMENTALS: "What's the difference between batch processing and stream processing?"
            TRICKY: "If your ETL job fails halfway through, what happens to the data that was already processed?"
            SCENARIO: "You need to move 10 million records from a MySQL database to a data warehouse daily — how do you approach that?"
            SCENARIO: "Your pipeline is running slower than expected — what are the first things you check?"
            PROJECT: "Walk me through a data pipeline you built — what tools did you use and why?"
            BEHAVIORAL: "What's something about data engineering you found much harder in practice than in theory?"
            """
        : """
            FUNDAMENTALS: "How does Apache Spark handle data partitioning — what happens when your partitions are skewed?"
            FUNDAMENTALS: "What's the difference between Kafka and a traditional message queue like RabbitMQ — when does Kafka's model break down?"
            TRICKY: "What does 'exactly-once semantics' mean in a streaming pipeline — why is it hard to achieve?"
            TRICKY: "What's a data pipeline anti-pattern you've seen that looked fine but caused real problems at scale?"
            DESIGN: "How would you design a real-time analytics pipeline that needs to handle 100,000 events per second?"
            DESIGN: "How do you ensure data quality across a complex pipeline with multiple transformation stages?"
            PROJECT: "Tell me about the most complex data pipeline you've built — what were the failure modes and how did you handle them?"
            BEHAVIORAL: "Tell me about a time a data pipeline failure caused a downstream impact — how did you manage it?"
            """;

    case "devops_engineer" -> fresher ? """
        FUNDAMENTALS: "What's the difference between a Docker image and a container — can you walk me through it?"
        FUNDAMENTALS: "What does a CI/CD pipeline actually do — can you walk me through the stages?"
        FUNDAMENTALS: "What is a Kubernetes pod — how is it different from a Docker container?"
        TRICKY: "If two containers are in the same Kubernetes pod, can they share the same port?"
        TRICKY: "What happens to running containers if the Docker daemon crashes?"
        SCENARIO: "Your deployment just failed in production — what's your first step?"
        SCENARIO: "You need to set up a CI pipeline for a Java application — what stages would you include?"
        PROJECT: "Walk me through a CI/CD pipeline you set up — what tools did you use?"
        BEHAVIORAL: "What's a DevOps concept that was confusing at first but now makes complete sense to you?"
        """
        : """
            FUNDAMENTALS: "How does Kubernetes decide which node to schedule a pod on — what factors does the scheduler consider?"
            FUNDAMENTALS: "What actually happens during a rolling deployment in Kubernetes — at the networking and pod lifecycle level?"
            TRICKY: "If a Kubernetes liveness probe fails, what happens exactly — how is that different from a readiness probe failing?"
            TRICKY: "Can two services in different Kubernetes namespaces talk to each other — how, and what are the security implications?"
            DESIGN: "How would you design a zero-downtime deployment pipeline for a stateful service?"
            DESIGN: "Your Kubernetes cluster is running out of resources unpredictably — how do you investigate and fix that?"
            PROJECT: "Tell me about a production outage you handled — what was your incident response process?"
            BEHAVIORAL: "Tell me about a time you automated something that was previously done manually — what was the impact?"
            """;

    case "cloud_engineer" -> fresher ? """
        FUNDAMENTALS: "What's the difference between IaaS, PaaS, and SaaS — can you give a real example of each?"
        FUNDAMENTALS: "What is a VPC and why do you need one?"
        FUNDAMENTALS: "What's the difference between horizontal and vertical scaling?"
        TRICKY: "If your EC2 instance runs out of disk space, does adding more RAM help? Why or why not?"
        SCENARIO: "You need to host a simple web application on AWS — what services would you use and why?"
        SCENARIO: "Your cloud bill is unexpectedly high this month — how do you investigate what's causing it?"
        PROJECT: "Have you set up anything on AWS, GCP, or Azure — walk me through what you built."
        BEHAVIORAL: "What's a cloud concept that surprised you once you actually started working with it?"
        """
        : """
            FUNDAMENTALS: "How does AWS Auto Scaling decide when to add or remove instances — what are the failure modes of that decision?"
            FUNDAMENTALS: "What's the difference between AWS SQS and SNS — when would you use them together?"
            TRICKY: "Your Lambda function is timing out intermittently but only under load — where do you start?"
            TRICKY: "What's a common cloud architecture pattern that looks good on paper but fails badly in practice?"
            DESIGN: "How would you design a multi-region active-active architecture for a high-availability service?"
            DESIGN: "How do you architect for cost efficiency without sacrificing reliability in a cloud-native app?"
            PROJECT: "Walk me through a cloud infrastructure you designed from scratch — what tradeoffs did you make?"
            BEHAVIORAL: "Tell me about a cloud cost or reliability issue you identified and fixed — what was the impact?"
            """;

    case "qa_automation_engineer" -> fresher
        ? """
            FUNDAMENTALS: "What's the difference between manual testing and automated testing — when would you choose one over the other?"
            FUNDAMENTALS: "What's the difference between a test case and a test suite?"
            FUNDAMENTALS: "What is a regression test — why does it matter?"
            TRICKY: "Can 100% test coverage guarantee your software has no bugs? Why or why not?"
            SCENARIO: "You need to write automated tests for a login form — what test cases would you write?"
            SCENARIO: "A bug was reported in production that your tests didn't catch — what's your first reaction?"
            PROJECT: "Have you written automated tests in any project — what framework did you use?"
            BEHAVIORAL: "What's something about software testing that you found more nuanced than you expected?"
            """
        : """
            FUNDAMENTALS: "How does Selenium WebDriver communicate with a browser — what actually happens under the hood?"
            FUNDAMENTALS: "What's the difference between black-box and white-box testing — when does each one catch bugs the other misses?"
            TRICKY: "What's a flaky test and how do you systematically diagnose and eliminate one?"
            TRICKY: "When should you NOT automate a test case — what signals tell you manual is better?"
            DESIGN: "How would you design a test automation framework from scratch for a large microservices application?"
            DESIGN: "How do you build a quality strategy that keeps up with a fast-moving agile team without blocking releases?"
            PROJECT: "Tell me about an automation framework you built or significantly improved — what was the before and after?"
            BEHAVIORAL: "Tell me about a time you caught a critical bug that others missed — how did you find it?"
            """;

    case "mobile_developer" -> fresher
        ? """
            FUNDAMENTALS: "What's the difference between Android and iOS development — at a platform level?"
            FUNDAMENTALS: "What is an Activity lifecycle in Android (or ViewController lifecycle in iOS) — why does it matter?"
            FUNDAMENTALS: "What's the difference between running code on the main thread vs a background thread in mobile?"
            TRICKY: "What happens to your app's state when the user rotates the screen on Android?"
            SCENARIO: "You're building a simple todo app for mobile — how do you persist data locally?"
            SCENARIO: "Your app is consuming too much battery — where do you start investigating?"
            PROJECT: "Walk me through a mobile app you built — what was the trickiest part?"
            BEHAVIORAL: "What's something about mobile development that's different from what you expected?"
            """
        : """
            FUNDAMENTALS: "How does Android's Jetpack Compose (or SwiftUI) differ from the traditional View system — what are the real tradeoffs?"
            FUNDAMENTALS: "What is a memory leak in mobile apps — what are the most common causes in Android or iOS?"
            TRICKY: "If your API call is running on a background thread and you try to update the UI from it, what happens — and how do you fix it?"
            TRICKY: "What's the difference between a cold start and a warm start — how do you optimize each?"
            DESIGN: "How would you architect offline support in a mobile app that syncs with a backend?"
            DESIGN: "How do you design a mobile release process that lets you ship fast without breaking production?"
            PROJECT: "Tell me about the most technically challenging feature you shipped in a mobile app — what made it hard?"
            BEHAVIORAL: "Tell me about a time a mobile app crashed in production — how did you diagnose and fix it?"
            """;

    case "software_architect" -> fresher
        ? """
            FUNDAMENTALS: "What's the difference between monolithic and microservices architecture — when would you choose one?"
            FUNDAMENTALS: "What does 'separation of concerns' mean in software design?"
            TRICKY: "Can microservices make things worse? Give me a case where they would."
            SCENARIO: "If you were designing a simple e-commerce backend, how would you think about splitting it into services?"
            PROJECT: "Walk me through the architecture of the most complex system you've worked on."
            BEHAVIORAL: "What architectural decision are you most proud of — and what would you do differently?"
            """
        : """
            FUNDAMENTALS: "How do you decide the right boundaries for a microservice — what signals tell you a service is too big or too small?"
            FUNDAMENTALS: "What's the CAP theorem and when have you had to consciously make a CAP tradeoff in a real system?"
            TRICKY: "What are the failure modes of an event-driven architecture that synchronous systems don't have?"
            DESIGN: "How would you migrate a large monolith to microservices without taking the whole system down?"
            DESIGN: "How do you architect for observability — what does a good monitoring and alerting strategy look like?"
            PROJECT: "Walk me through the most consequential architectural decision you've made — what was the long-term impact?"
            BEHAVIORAL: "Tell me about a time your architectural recommendation was rejected — how did you handle it?"
            """;

    case "engineering_manager" -> fresher
        ? """
            FUNDAMENTALS: "What's the difference between a manager's role and a tech lead's role?"
            FUNDAMENTALS: "How do you think about prioritizing work across a team when everything feels urgent?"
            TRICKY: "If two senior engineers on your team disagree on an architecture decision, what do you do?"
            SCENARIO: "Imagine your team is going to miss a deadline — how do you handle the conversation with stakeholders?"
            PROJECT: "Walk me through a time you coordinated a project across multiple people."
            BEHAVIORAL: "How do you give feedback to someone who gets defensive?"
            """
        : """
            FUNDAMENTALS: "How do you decide when a project needs more engineers versus better process or tooling?"
            FUNDAMENTALS: "What does 'psychological safety' actually mean in practice — how do you build it deliberately?"
            TRICKY: "Your best engineer wants to rewrite the entire codebase. How do you handle that conversation?"
            TRICKY: "You have a high performer who is toxic to the team's culture. What do you do?"
            DESIGN: "How would you structure a hiring process that filters for engineering judgment, not just coding speed?"
            DESIGN: "How do you set up a team's on-call process that's sustainable and doesn't burn people out?"
            PROJECT: "Tell me about a time a project you were managing went off the rails — how did you recover it?"
            BEHAVIORAL: "Tell me about a time you had to let someone go — how did you handle it?"
            """;

    case "product_manager" -> fresher ? """
        FUNDAMENTALS: "What's the difference between a product manager and a project manager?"
        FUNDAMENTALS: "How do you decide what features to build when you have more requests than capacity?"
        TRICKY: "A feature has high user demand but low business value — do you build it? How do you decide?"
        SCENARIO: "Walk me through how you would approach building a roadmap for a new product from scratch."
        PROJECT: "Tell me about a product decision you made — how did you validate it was the right one?"
        BEHAVIORAL: "Tell me about a time engineering pushed back on your requirements — how did you handle it?"
        """
        : """
            FUNDAMENTALS: "How do you balance short-term user needs against long-term strategic direction in a roadmap?"
            FUNDAMENTALS: "What metrics do you use to know if a feature you shipped was actually successful?"
            TRICKY: "How do you make a prioritization decision when the data and stakeholder pressure point in opposite directions?"
            DESIGN: "How would you design a go-to-market strategy for a new B2B product entering a crowded market?"
            DESIGN: "Walk me through your process for running an effective product discovery sprint."
            PROJECT: "Tell me about a product bet that didn't work out — what did you learn and what would you do differently?"
            BEHAVIORAL: "Tell me about a time you had to kill a feature or product that people had invested in — how did you manage that?"
            """;

    case "hr_recruiter" -> fresher
        ? """
            FUNDAMENTALS: "What's the difference between sourcing and recruiting?"
            FUNDAMENTALS: "How do you evaluate a candidate's cultural fit without being biased?"
            TRICKY: "A strong candidate accepts your offer and then backs out the day before joining — what do you do?"
            SCENARIO: "You need to fill a senior Java developer role in 30 days — how do you approach it?"
            PROJECT: "Walk me through a hiring process you've been part of — what worked and what didn't?"
            BEHAVIORAL: "Tell me about a time you had a difficult conversation with a hiring manager — how did you handle it?"
            """
        : """
            FUNDAMENTALS: "How do you build a talent pipeline for roles that are hard to fill — what's your sourcing strategy?"
            FUNDAMENTALS: "How do you reduce bias in a hiring process without slowing it down?"
            TRICKY: "How do you handle a situation where the hiring manager's bar is so high that you're rejecting good candidates?"
            DESIGN: "How would you redesign an interview process that has a high offer rejection rate?"
            DESIGN: "How do you build an employer brand strategy that attracts passive candidates?"
            PROJECT: "Tell me about a critical hire you made that had a big impact — what was your process?"
            BEHAVIORAL: "Tell me about a time you pushed back on a hiring decision you disagreed with — what happened?"
            """;

    // ── Generic fallback for any unmapped role ──
    default -> fresher
        ? """
            FUNDAMENTALS: "Can you walk me through how [core concept in your area] actually works — not the textbook definition, but how you understand it?"
            FUNDAMENTALS: "What's the difference between X and Y in your field — when would you choose one over the other?"
            TRICKY: "Here's a common assumption people make about [topic] — is it always true? When does it break?"
            SCENARIO: "Imagine you're building a simple version of [common problem in this field] from scratch — how would you start?"
            PROJECT: "Tell me about a project you worked on that you learned the most from — what was the hardest part?"
            BEHAVIORAL: "When you run into something in your field that you don't understand, what's your process for figuring it out?"
            """
        : """
            FUNDAMENTALS: "How does [core technology or concept] actually behave when [edge case or pressure] — what have you seen go wrong?"
            TRICKY: "Give me a case where the obvious approach to [common problem] completely falls apart in practice."
            DESIGN: "How would you design [common system in this role] to handle [realistic constraint] — walk me through your thinking."
            PROJECT: "Tell me about the most impactful thing you've built or delivered — what was the decision that made the biggest difference?"
            BEHAVIORAL: "Tell me about a time you disagreed with a technical or strategic decision — what did you do and what was the outcome?"
            """;
    };
  }

}
