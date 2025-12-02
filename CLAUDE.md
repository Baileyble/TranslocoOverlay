# TranslocoOverlay - IntelliJ/WebStorm Plugin

A plugin for IntelliJ-based IDEs (WebStorm, IntelliJ IDEA) that provides hover-to-view and inline editing for Transloco translation keys in Angular templates.

## Project Overview

**Goal**: When a user hovers over a Transloco key in an Angular template (e.g., `{{ 'user.profile.name' | transloco }}`), the plugin resolves the key to its JSON translation file and displays an overlay allowing the user to view and edit the translation value.

## Tech Stack

- **Language**: Kotlin
- **Build System**: Gradle (Kotlin DSL)
- **Platform**: IntelliJ Platform SDK
- **Target IDEs**: WebStorm, IntelliJ IDEA (with JavaScript plugin)

## Build & Run Commands

```bash
# Build the plugin
./gradlew build

# Run plugin in a sandboxed IDE instance for testing
./gradlew runIde

# Run tests
./gradlew test

# Build distribution zip
./gradlew buildPlugin

# Verify plugin compatibility
./gradlew verifyPlugin
```

## Project Structure

```
src/main/
├── kotlin/com/baileyble/translocooverlay/
│   ├── TranslocoReferenceContributor.kt    # Registers reference providers
│   ├── TranslocoReferenceProvider.kt       # Detects transloco keys in templates
│   ├── TranslocoKeyReference.kt            # Resolves keys to JSON locations
│   ├── TranslocoDocumentationProvider.kt   # Hover overlay content
│   ├── TranslocoEditAction.kt              # Inline editing functionality
│   ├── TranslocoCompletionContributor.kt   # Autocomplete for keys
│   └── util/
│       ├── TranslocoConfigFinder.kt        # Finds transloco.config.ts
│       ├── JsonKeyNavigator.kt             # Navigate nested JSON keys
│       └── TranslationFileFinder.kt        # Locate i18n JSON files
└── resources/
    └── META-INF/
        └── plugin.xml                       # Plugin configuration

src/test/kotlin/                             # Unit tests
```

## Key Concepts

### Transloco Key Patterns to Detect
- Pipe syntax: `{{ 'key.path' | transloco }}`
- Pipe with params: `{{ 'key.path' | transloco:params }}`
- Attribute binding: `[transloco]="'key.path'"`
- Structural directive: `*transloco="let t; read: 'scope'"`
- Direct attribute: `transloco="key.path"`
- Service usage: `this.transloco.translate('key.path')`

### Translation File Locations
- Default: `assets/i18n/{lang}.json` or `src/assets/i18n/{lang}.json`
- Check `transloco.config.ts` for custom paths
- Support scoped translations in feature modules

### IntelliJ Platform APIs to Use

**PSI (Program Structure Interface)**
- `PsiReferenceContributor` - Register reference providers
- `PsiReferenceProvider` - Create references from PSI elements
- `PsiReference` - Link from usage to definition

**Documentation**
- `AbstractDocumentationProvider` - Hover documentation
- `DocumentationProvider.generateDoc()` - Returns HTML for hover popup

**Editing**
- `WriteCommandAction` - Wrap all file modifications
- `JsonElementGenerator` - Create new JSON elements
- `PsiElement.replace()` - Replace PSI elements

**UI Components**
- `JBPopupFactory` - Create custom popups
- `IntentionAction` - Alt+Enter quick actions
- `EditorHintListener` - Editor hints/tooltips

**Indexing**
- `FileBasedIndex` - Custom indexes for fast key lookup
- `FilenameIndex` - Find files by name
- `GlobalSearchScope` - Define search boundaries

## Dependencies

Required plugin dependencies in `plugin.xml`:
```xml
<depends>com.intellij.modules.platform</depends>
<depends>JavaScript</depends>
<depends>com.intellij.modules.json</depends>
```

## Development Notes

### JSON PSI Classes
- `JsonFile` - Root JSON file
- `JsonObject` - JSON object `{}`
- `JsonProperty` - Key-value pair
- `JsonStringLiteral` - String value
- `JsonArray` - Array `[]`

### Template Detection
Angular templates can be:
- Inline in `@Component({ template: '...' })`
- External `.html` files
- Use `Angular2TemplateUsageProvider` if available

### Key Resolution Strategy
1. Extract key string from PSI element
2. Find translation JSON files in project
3. Parse dot-notation path (e.g., `user.profile.name`)
4. Navigate JSON tree to find target property
5. Return `JsonStringLiteral` as resolution target

### Caching
Use `CachedValuesManager` for:
- Translation file locations
- Parsed translation keys
- Config file contents

## Testing

- Use `LightPlatformCodeInsightFixture4TestCase` for PSI tests
- Create test fixtures with sample Angular templates and JSON files
- Test key detection, resolution, and editing

## References

- [IntelliJ Platform SDK Docs](https://plugins.jetbrains.com/docs/intellij/)
- [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- [Transloco Documentation](https://ngneat.github.io/transloco/)
- [JSON Plugin Source](https://github.com/JetBrains/intellij-community/tree/master/json)

## Common Issues

- **"Read access is allowed from inside read-action"**: Wrap PSI access in `ReadAction.compute { }`
- **"Write access is allowed from write-action only"**: Use `WriteCommandAction.runWriteCommandAction()`
- **Plugin not detecting files**: Check `plugin.xml` dependencies and file type associations
