plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.7.1"
}

group = "com.baileyble"
version = "2.2.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure IntelliJ Platform Gradle Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        // Target WebStorm 2026.1 (build 261) for 2026 compatibility
        create("WS", "2026.1")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // JavaScript/Angular support is bundled in WebStorm
        bundledPlugin("JavaScript")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }  // No upper limit
        }

        changeNotes = """
            <h3>Version 2.2.0 - IntelliJ 2026 Support</h3>
            <ul>
                <li><b>NEW: IntelliJ 2026 Compatibility</b> - Updated plugin to support IntelliJ 2026.1 and later versions</li>
                <li><b>NOTE:</b> This version requires IntelliJ 2026.1+. For IntelliJ 2025.x, please use version 2.1.1</li>
            </ul>

            <h3>Version 2.1.1 - Bug Fix</h3>
            <ul>
                <li><b>FIX: Ctrl+Click false positives</b> - Fixed issue where Ctrl+Click on unrelated elements (like Angular component tags) would incorrectly trigger the Transloco overlay when a transloco key existed elsewhere in the template</li>
            </ul>

            <h3>Version 2.0.0 - Create Translation from Selection</h3>
            <ul>
                <li><b>NEW: Create Translation Shortcut</b> - Select text in HTML, Ctrl+Shift+Click to create a new translation</li>
                <li><b>NEW: Existing Translation Detection</b> - Automatically finds if selected text already exists as a translation</li>
                <li><b>NEW: Method Selector</b> - Choose between Pipe syntax or Directive syntax for new translations</li>
                <li><b>NEW: Parameter Support</b> - Detects Angular interpolations (e.g., {{user.name}}) and converts them to transloco params</li>
                <li><b>NEW: TRANSLOCO_SCOPE Detection</b> - Automatically detects scoped translations from component providers</li>
                <li><b>NEW: Real-time Key Validation</b> - Shows warning if translation key already exists while typing</li>
                <li><b>NEW: Directive Context Detection</b> - Detects *transloco directive variable names and scopes</li>
                <li><b>IMPROVED: Multi-language Translation Dialog</b> - Enhanced UI with live preview</li>
                <li><b>IMPROVED: Google Translate Integration</b> - Translate individual languages or all at once</li>
            </ul>
        """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
