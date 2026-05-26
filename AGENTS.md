# PROJECT KNOWLEDGE BASE - GhidrAssist

## CRITICAL POLICIES
- **GRADLE BUILD**: The lack of `gradlew` in this directory is INTENTIONAL. This project MUST be built using the Gradle wrapper at the parent directory: `/opt/github/GhidraGround/gradlew`.
  - ✅ Correct: `/opt/github/GhidraGround/gradlew build`
  - ❌ Incorrect: `gradle build` or searching for a local `gradlew`.
- **PREVIEW FEATURES**: Use `--enable-preview` to enable unnamed classes and other Java preview features if required by the build.

## OVERVIEW
Advanced LLM-powered plugin for interactive reverse engineering assistance in Ghidra.

## STRUCTURE
- `src/main/java/ghidrassist/apiprovider/`: API provider implementations (OpenAI, Google GenAI, etc.)
- `src/main/java/ghidrassist/services/`: Core services (Query, Action Analysis, SymGraph)
- `src/main/java/ghidrassist/ui/`: UI components and tabs

## ONGOING WORK
- Version bump to 1.28.3 completed.
- Maintenance and feature enhancements.

## COMMANDS
```bash
# Build the project
/opt/github/GhidraGround/gradlew build

# Run tests
/opt/github/GhidraGround/gradlew test
```
