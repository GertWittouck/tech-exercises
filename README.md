# Test exercises

Solutions to technical take-home exercises. Each folder is a self-contained project with its own build, tests and README.

| Exercise | Language | Build | What it covers |
|----------|----------|-------|----------------|
| [SimpleCache review and Hierarchy filter](<folder>/) | Kotlin | Gradle | Reviewing a concurrent TTL cache: issues proven by tests, a corrected version, and a single-pass tree filter |

## How each exercise is approached

- **Read the brief carefully.** Each folder keeps the original task, so the solution can be checked against what was asked.
- **Prove before fixing.** Issues found in a review are first shown with a failing (or bug-asserting) test, then fixed test-first.
- **Explain the reasoning.** Each exercise has a write-up covering the approach, the assumptions and the trade-offs, not only the code.

## Running an exercise

Each project includes a build wrapper (Gradle or Maven, see the table above), so only a JDK is needed. The wrapper downloads the build tool itself.

**Gradle** projects:

```bash
cd <folder>
./gradlew test        # Windows: gradlew.bat test
```

**Maven** projects:

```bash
cd <folder>
./mvnw test           # Windows: mvnw.cmd test
```

See the README in each folder for details and the required JDK version.
