# Contributing

Bug reports and ideas are welcome. Issues in English or Korean are fine.

## Reporting a bug

Open an [issue](https://github.com/ukukj/comment-checker-jetbrains/issues) with:

- IDE and version (Help → About) and plugin version (Settings → Plugins).
- Language of the file (Java, Kotlin, Python, TypeScript, …).
- A small snippet before and after the change, what the Comments tab showed, and what you expected. A screenshot of the tab helps.
- Errors from the IDE log (Help → Show Log in Finder/Explorer), if any.

Don't paste code you can't share publicly. A made-up snippet that reproduces the problem is enough.

## Ideas

Open an issue before a large pull request, so the behavior is agreed on first.

## Pull requests

- JDK 21. Build and test commands are in [README › Development](README.md#development).
- Add a test for changed rules under `src/test/kotlin/dev/commentchecker`.
- Run `./gradlew test` and `./gradlew verifyPlugin` before opening the PR.
- UI text goes in both `CommentCheckerBundle.properties` and `CommentCheckerBundle_ko.properties`. If you don't read Korean, copy the English text into the `_ko` file; it will be translated before release.
