# StreamLens Java Candidate Guide / Гайд для кандидата

This is the candidate-facing guide for the StreamLens Java interview. It
summarizes the workflow in English and Ukrainian. The two sections describe
the same process. TASK.md and PRD.md remain the authoritative contract.

Before a scored session, the interviewer must confirm that java-v6 is active
from RELEASES.md and the trusted assessment workflow on the upstream default
branch. Do not use a baseline tag or candidate branch as the live release
record.

## English

### Your task

You have up to 30 minutes to make the supplied Java analyzer faster while
preserving observable behavior.

- AI assistants, IDEs, profilers, debuggers, and local analysis tools are allowed.
- Change exactly two files:
  - src/main/java/com/streamlens/analyzer/Analyzer.java
  - OPTIMIZATION.md
- Correctness matters as much as speed. Do not bypass validation, recognize
  workloads, skip valid work, approximate results, or reorder input-order sums.
- Keep the change ordinary, focused, and explainable in a review.

The timer begins only after a clean checkout and a working Java 21 environment.
It stops at 30:00 or when you record your final local commit SHA, whichever
comes first. Setup, push, pull-request creation, CI queue/runtime, and reading
the report are not timed.

### Before the timer

1. Ask the interviewer to confirm the active release and provide a clean
   checkout. Report a Java or repository setup problem before the timer starts.
2. Fork if required, then create your branch directly from immutable
   baseline-v6:

   ~~~sh
   git clone <your-fork-url>
   cd streamlens-java-performance-challenge
   git remote add upstream <upstream-url>
   git fetch upstream --tags
   git switch --detach baseline-v6
   git switch -c optimize-analyzer
   ~~~

3. Do not merge, rebase, pull, or otherwise update this candidate branch from
   the upstream default branch. The assessment compares baseline-v6..candidate,
   so later upstream changes are out of scope.
4. Confirm the environment and collect starting evidence:

   ~~~sh
   java -version
   make check
   make benchmark
   make profile-cpu
   # Or: make profile-alloc
   ~~~

### During the 30 minutes

1. Read README.md and TASK.md. Use PRD.md and DESIGN.md for behavior that must
   be preserved. If you use AI, give it AGENTS.md.
2. Run a measured CPU or allocation profile and identify a real hotspot. Code
   reading alone is not profile evidence.
3. Form a small, testable hypothesis and change only Analyzer.java.
4. Preserve strict UTF-8 and JSON handling, validation and error order,
   filtering, window semantics, deterministic ordering, interruption behavior,
   and input-order Java double addition.
5. Stay within the safe Java 21 subset for Analyzer.java. Do not add filesystem,
   network, subprocess, reflection, logging/output, JVM-control, or thread
   management behavior.
6. Rerun make check and the measurements relevant to your hypothesis.
7. Replace OPTIMIZATION.md with 5-10 concise, truthful bullets. Include:
   - Profile evidence: the command/tool and hotspot you actually observed.
   - What changed, why, and measured local or clearly labelled expected effect.
   - Correctness considerations, trade-offs, and verification.
8. Before the deadline, inspect and commit only the two permitted files:

   ~~~sh
   git diff --check
   git status --short
   git add src/main/java/com/streamlens/analyzer/Analyzer.java OPTIMIZATION.md
   git commit -m optimize-event-analysis
   git rev-parse HEAD
   ~~~

9. Record that exact SHA before 30:00. Do not change the implementation or
   notes after recording it.

### After the timer: submit the exact SHA

1. Push the recorded commit and open a pull request to the upstream default
   branch:

   ~~~sh
   git push -u origin optimize-analyzer
   ~~~

2. Ensure the pull-request head is the SHA you recorded. Do not amend it after
   the timer.
3. Complete the pull-request template and inspect the Actions summary. A forked
   workflow can require maintainer approval; approval and queue time are untimed.

### What result you receive

The public Actions report tells you whether the submitted SHA passed:

- the two-file scope and safe-JDK source-policy checks;
- correctness and response-format checks; and
- baseline-versus-candidate measurements for Balanced, HighCardinality, and
  MostlyFiltered.

It reports geometric-mean improvement for execution time (ns/op) and normalized
allocation volume (B/op). The higher metric tier is reported:

| Improvement in one metric | Reported tier |
| --- | --- |
| Less than 20% | Below target |
| 20%-49.99% | Middle |
| 50%-74.99% | Senior |
| 75% or more | Staff |

To pass, one aggregate metric must improve by 20% or more, the other may not
regress by more than 20%, and no scenario/metric pair may regress by more than
30%. A scope, source-policy, correctness, or response-format failure takes
priority over performance.

The public report is necessary evidence, not the full interview outcome.
Interviewers also use a separately maintained private evaluator for the same
SHA and a short debrief about your evidence, code, constraints, and trade-offs.
Private test contents are not published. A Middle, Senior, or Staff tier
describes this change against this baseline; it is not a job level or hiring
decision.

If infrastructure fails, the result is no score rather than candidate failure;
the interviewer can rerun the exact same SHA. Near a tier boundary, they may
rerun the same SHA once and retain the lower inconsistent result.

### What we expect

- Use AI with the task contract and evidence, rather than accepting a suggestion
  without review.
- Be ready to explain your profile observation, changed code, correctness
  risks, verification, and trade-off.
- Keep OPTIMIZATION.md honest: distinguish measured local data from expected
  effects, and never present an optimization tier as personal seniority.

You do not need to disclose private AI account history or unrelated prompts.
What matters is that you understand and own the submitted work.

## Українська

### Ваше завдання

У вас є до 30 хвилин, щоб прискорити наданий Java-аналізатор, не змінюючи
його спостережувану поведінку.

- Можна використовувати AI-асистентів, IDE, профайлери, дебагери та локальні
  інструменти аналізу.
- Можна змінювати рівно два файли:
  - src/main/java/com/streamlens/analyzer/Analyzer.java
  - OPTIMIZATION.md
- Коректність така ж важлива, як і швидкість. Не можна обходити валідацію,
  розпізнавати навантаження, пропускати валідну роботу, наближати результати
  або змінювати порядок додавання у вхідному потоці.
- Код має бути звичайним, сфокусованим і зрозумілим під час рев'ю.

Таймер запускається лише після чистого checkout і підтвердження, що проєкт
запускається на Java 21. Він зупиняється на 30:00 або коли ви зафіксували SHA
фінального локального коміту — залежно від того, що станеться раніше.
Клонування, налаштування, push, створення PR, черга/робота CI та читання звіту
не входять у відведений час.

### До старту таймера

1. Попросіть інтерв'юера підтвердити активний реліз і надати чистий checkout.
   Якщо Java 21 або налаштування репозиторію не працюють, повідомте про це до
   старту таймера.
2. За потреби зробіть fork репозиторію, а потім створіть кандидатську гілку
   безпосередньо від незмінного baseline-v6:

   ~~~sh
   git clone <your-fork-url>
   cd streamlens-java-performance-challenge
   git remote add upstream <upstream-url>
   git fetch upstream --tags
   git switch --detach baseline-v6
   git switch -c optimize-analyzer
   ~~~

3. Не робіть merge, rebase, pull і не оновлюйте кандидатську гілку з upstream
   default branch. Перевірка порівнює baseline-v6..candidate; пізніші зміни
   upstream виходять за межі дозволеного scope.
4. Перевірте середовище та зафіксуйте початкові дані:

   ~~~sh
   java -version
   make check
   make benchmark
   make profile-cpu
   # Або: make profile-alloc
   ~~~

### Протягом 30 хвилин

1. Прочитайте README.md і TASK.md. Для правил, які треба зберегти, звертайтеся
   до PRD.md і DESIGN.md. Якщо використовуєте AI-асистента, надайте йому
   AGENTS.md.
2. За допомогою виміряного CPU- або allocation-профілю знайдіть реальний
   hotspot. Лише читання коду не є profile evidence.
3. Сформулюйте невелику перевірювану гіпотезу і змінюйте тільки Analyzer.java.
4. Збережіть увесь контракт: strict UTF-8 і JSON, валідацію та порядок помилок,
   фільтрацію, семантику часових вікон, детерміноване сортування, interruption
   behavior і Java double-додавання у порядку вхідних подій.
5. Дотримуйтеся safe Java 21 subset для Analyzer.java. Не додавайте роботу з
   файловою системою, мережею, subprocess, reflection, logging/output,
   JVM-control чи керуванням потоками.
6. Повторно запустіть make check та вимірювання, які відповідають вашій
   гіпотезі.
7. Замініть OPTIMIZATION.md на 5-10 коротких правдивих пунктів. Укажіть:
   - Profile evidence: команду/інструмент і hotspot, який ви справді побачили.
   - Що і чому змінилося, а також виміряний локальний або чітко позначений
     очікуваний ефект.
   - Міркування щодо коректності, trade-off і перевірку.
8. До дедлайну перевірте й закомітьте лише два дозволені файли:

   ~~~sh
   git diff --check
   git status --short
   git add src/main/java/com/streamlens/analyzer/Analyzer.java OPTIMIZATION.md
   git commit -m optimize-event-analysis
   git rev-parse HEAD
   ~~~

9. Зафіксуйте цей точний SHA до 30:00. Після цього не змінюйте реалізацію чи
   нотатки.

### Після таймера: подайте точний SHA

1. Запуште зафіксований коміт і відкрийте pull request до upstream default
   branch:

   ~~~sh
   git push -u origin optimize-analyzer
   ~~~

2. Переконайтеся, що head pull request має той самий SHA, який ви зафіксували.
   Не робіть amend після таймера.
3. Заповніть шаблон pull request і прочитайте Actions summary. Workflow з fork
   може потребувати схвалення maintainer; схвалення та час у черзі не входять
   у таймер.

### Який результат ви отримаєте

Публічний звіт Actions покаже, чи пройшов поданий SHA:

- перевірку scope із двох файлів і safe-JDK source policy;
- перевірки коректності та формату відповіді; і
- вимірювання baseline проти candidate для сценаріїв Balanced, HighCardinality
  та MostlyFiltered.

Звіт показує geometric-mean покращення для часу виконання (ns/op) і
нормалізованого обсягу алокацій (B/op). Вказується вищий рівень із двох метрик:

| Покращення хоча б однієї метрики | Вказаний рівень |
| --- | --- |
| Менше 20% | Below target |
| 20%-49.99% | Middle |
| 50%-74.99% | Senior |
| 75% або більше | Staff |

Щоб пройти performance gate, хоча б одна агрегована метрика має покращитися
на 20% або більше, інша не може погіршитися більш ніж на 20%, а жодна пара
scenario/metric не може погіршитися більш ніж на 30%. Будь-який failure scope,
source policy, коректності або формату відповіді має пріоритет над performance.

Публічний звіт є необхідним evidence, але не повним результатом інтерв'ю.
Інтерв'юери також використовують окремо підтримуваний private evaluator для
того самого SHA та короткий debrief про ваші evidence, код, обмеження і
trade-off. Вміст private test не публікується. Рівень Middle, Senior або Staff
описує цю зміну відносно цього baseline; це не рівень посади й не рішення про
найм.

Якщо зламалася інфраструктура, результатом буде no score, а не failure
кандидата; інтерв'юер може повторити запуск для того самого SHA. Біля межі
рівня можна один раз повторити той самий SHA і зафіксувати нижчий результат,
якщо запуски дадуть різні рівні.

### Що ми очікуємо від вас

- Керуйте AI-інструментами через контракт завдання та evidence, а не приймайте
  пропозицію без перевірки.
- Умійте пояснити profile observation, змінений код, ризики для коректності,
  перевірку і свій trade-off.
- Залишайте твердження в OPTIMIZATION.md чесними: відділяйте виміряні локальні
  дані від очікувань і не подавайте optimization tier як особистий рівень
  seniority.

Не потрібно розкривати історію приватного AI-акаунта чи сторонні prompts.
Важливо, щоб ви розуміли та могли обґрунтувати подану роботу.
