# Transloco Overlay

An IntelliJ/WebStorm plugin that provides hover-to-view and inline editing for [Transloco](https://ngneat.github.io/transloco/) translation keys in Angular templates.

## Features

- **Hover** over translation keys to see their values across all languages
- **Ctrl+Click** on keys in HTML templates to navigate to JSON translation files
- **Ctrl+Shift+Click** on keys in HTML to open multi-language translation editor
- **Ctrl+Shift+Click** on keys in JSON files to find usages in templates
- Edit all language translations in one convenient dialog
- Google Translate integration for auto-translation
- Create new translation keys that don't exist yet
- Autocomplete for translation keys
- Support for Nx monorepo scoped translations

## Supported Patterns

- `{{ 'key' | transloco }}` - Pipe syntax
- `[transloco]="'key'"` - Attribute binding
- `transloco="key"` - Direct attribute
- `t('key')` with `*transloco` directive - Structural directive

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

## Contributing

Contributions are welcome! Feel free to:
- Report bugs or request features via [GitHub Issues](https://github.com/Baileyble/TranslocoOverlay/issues)
- Submit pull requests
- Fork the repository to customize for your needs

## Disclaimer

This plugin was created with AI assistance (Claude by Anthropic). The source code is open source and available for review. You are encouraged to:
- Review the code before installation
- Report any issues or concerns
- Create your own fork to modify for your specific internationalization strategy

## License

This project is open source. See the repository for license details.
