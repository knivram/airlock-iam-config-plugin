import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea("2025.2.6.2")
        // Bundled YAML plugin: puts org.jetbrains.yaml.* on the compile and test classpath.
        bundledPlugin("org.jetbrains.plugins.yaml")
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            // Require 2025.2+; leave until-build open for forward compatibility with newer IDEs.
            sinceBuild = "252"
            untilBuild = provider { null }
        }

        // Populate the plugin's <change-notes> from CHANGELOG.md: render the section matching the build's
        // version (falling back to the Unreleased section) as HTML. Keeps the marketplace / IDE "What's new"
        // in sync with the changelog without hand-maintaining a second copy. `description` stays hand-authored
        // in plugin.xml and is intentionally left unset here.
        changeNotes = providers.gradleProperty("version").map { version ->
            with(changelog) {
                renderItem(
                    (getOrNull(version) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }
    }
}
