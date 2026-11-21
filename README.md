<p align="center">
  <img src="assets/logo.svg" alt="rethinkj" width="96">
</p>

# rethinkj Test

A fast test runner for Java, built for the loop of a coding agent: edit, test, read, edit again. It runs the JUnit Jupiter tests a project already has, unchanged.

## Status

Nothing is implemented yet. The first steps are measuring where the time of a test run goes and a runner that keeps its JVM warm between runs. It targets Maven projects on Windows 11 and Linux, with JDK 25; Gradle and Spring come later.

## What it is meant to do

- **Stay warm**: one JVM per project that keeps the libraries loaded and compiled between runs, and loads the project's own classes fresh for every run.
- **Run what a change affects**: only the tests whose classes a change touches, the new ones and the ones that failed last time. When it cannot tell, it runs every test. CI still runs them all.
- **Report for agents**: one line when everything passes; for a failure, the file and line of the assertion, what was expected and what came, and the command that runs it again.
- **Report on every edit**: the affected tests run as soon as a file is saved, and the result reaches the agent with the edit, without a turn of its own.

## License

[MIT](LICENSE)
