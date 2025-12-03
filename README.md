# Transloco Overlay

A powerful IntelliJ/WebStorm plugin for managing [Transloco](https://ngneat.github.io/transloco/) translations in Angular projects. Create, edit, and navigate translations directly from your HTML templates.

## ✨ Key Features (v2.0)

### Create Translations from Selection
- **Select text** in HTML and **Ctrl+Shift+Click** to instantly create a new translation
- **Existing translation detection** - Automatically finds if selected text already exists to avoid duplicates
- **Smart parameter detection** - Detects Angular interpolations like `{{user.name}}` and converts them to transloco params
- **Method selector** - Choose between Pipe (`| transloco`) or Directive (`t('key')`) syntax
- **Real-time key validation** - Shows warnings if a translation key already exists while you type
- **TRANSLOCO_SCOPE detection** - Auto-detects scoped translations from component providers
- **Directive context detection** - Automatically detects `*transloco` variable names and scopes

### Navigation & Editing
- **Hover** over translation keys to see their values across all languages
- **Ctrl+Click** on keys in HTML templates to navigate to JSON translation files
- **Ctrl+Shift+Click** on existing keys in HTML to open multi-language translation editor
- **Ctrl+Shift+Click** on keys in JSON files to find usages in templates
- **Autocomplete** for translation keys with value preview
- **Google Translate** integration for auto-translation (individual or all languages)

### Enterprise Ready
- Support for **Nx monorepo** scoped translations
- Works with multiple translation file locations
- Remembers your last used location and method preferences

## Supported Patterns

| Pattern | Description |
|---------|-------------|
| `{{ 'key' \| transloco }}` | Pipe syntax |
| `{{ 'key' \| transloco:{ param: value } }}` | Pipe with params |
| `[transloco]="'key'"` | Attribute binding |
| `transloco="key"` | Direct attribute |
| `t('key')` with `*transloco` | Structural directive |

## Usage Examples

### Creating a New Translation
1. Select text in your HTML template (e.g., `Hello, {{user.name}}!`)
2. Press **Ctrl+Shift+Click**
3. The dialog will:
   - Detect the `{{user.name}}` interpolation and suggest `name` as a param
   - Check if this text already exists as a translation
   - Auto-detect if you're inside a `*transloco` directive
   - Pre-select the correct translation file based on `TRANSLOCO_SCOPE`
4. Enter your translation key (e.g., `greeting.hello`)
5. Click OK to create the translation and replace the text

### Editing Existing Translations
1. Place cursor on a translation key (e.g., `'user.profile.title'`)
2. Press **Ctrl+Shift+Click**
3. Edit translations for all languages in the dialog
4. Use "Translate" buttons for auto-translation

## Installation

### From JetBrains Marketplace
1. Open your IDE (WebStorm, IntelliJ IDEA)
2. Go to **Settings/Preferences** > **Plugins** > **Marketplace**
3. Search for "Transloco Overlay"
4. Click **Install**

### From Source
1. Clone this repository
2. Run `./gradlew buildPlugin`
3. Install the generated `.zip` file from `build/distributions/` via **Settings** > **Plugins** > **Install Plugin from Disk**

## Development

```bash
# Build the plugin
./gradlew build

# Run plugin in a sandboxed IDE instance
./gradlew runIde

# Run tests
./gradlew test

# Build distribution zip
./gradlew buildPlugin
```

## Requirements

- IntelliJ IDEA 2024.1+ or WebStorm 2024.1+
- JavaScript plugin (bundled with WebStorm, available for IntelliJ IDEA)

## Changelog

### Version 2.0.0
- **NEW:** Create Translation from Selection - Select text and Ctrl+Shift+Click to create translations
- **NEW:** Existing Translation Detection - Finds duplicate translations before creating new ones
- **NEW:** Parameter Support - Detects `{{expression}}` interpolations and converts to transloco params
- **NEW:** Method Selector - Choose between Pipe and Directive syntax
- **NEW:** TRANSLOCO_SCOPE Detection - Auto-detects scoped translations from component providers
- **NEW:** Real-time Key Validation - Warns if key already exists while typing
- **NEW:** Directive Context Detection - Detects `*transloco` variable names and scopes
- **IMPROVED:** Multi-language Translation Dialog with live preview
- **IMPROVED:** Google Translate integration

### Version 1.0.0
- Initial release
- Hover documentation for translation keys
- Ctrl+Click navigation to JSON files
- Multi-language translation editor
- Autocomplete for translation keys
- Nx monorepo support

## Contributing

Contributions are welcome! Feel free to:
- Report bugs or request features via [GitHub Issues](https://github.com/Baileyble/TranslocoOverlay/issues)
- Submit pull requests
- Fork the repository to customize for your needs

## AI Transparency

This plugin was **created using AI** (Claude by Anthropic). The source code is fully open source and available for your review before installation.

You are encouraged to:
- **Inspect the code** before installing
- Report any issues via GitHub Issues
- Create your own fork for custom internationalization strategies

## License

This project is open source. See the repository for license details.
