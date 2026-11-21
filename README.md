<p align="center">
  <img src="assets/logo.svg" alt="rethinkj" width="96">
</p>

# rethinkj Test

A fast test runner for Java, built for the loop of a coding agent: edit, test, read, edit again. It runs the JUnit Jupiter tests a project already has, unchanged.

## Status

The report for agents works, with `mvn test`. The runner that keeps its JVM warm comes next. It targets Maven projects on Windows 11 and Linux, with JDK 25; Gradle and Spring come later. The version is `0.1.0-SNAPSHOT` and is not published yet: `mvn install` puts it into the local repository.

## Report for agents

Add the library to the tests:

```xml
<dependency>
  <groupId>com.rethinkj.test</groupId>
  <artifactId>rethinkj-test-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>test</scope>
</dependency>
```

Or, without changing the project: `-Dmaven.test.additionalClasspath=<path to the JAR>`.

Then name the file the report goes to; a relative path is relative to the module:

```sh
mvn test -Drethinkj.test.report=target/test-report.jsonl
```

Each line is one JSON object. A failure is written as it happens:

```json
{"v":1,"event":"failed","test":"com.example.VersionTest#ordersBySemVer","name":"ordersBySemVer()","id":"[engine:junit-jupiter]/[class:com.example.VersionTest]/[method:ordersBySemVer()]","at":"com/example/VersionTest.java:35","error":"org.opentest4j.AssertionFailedError","message":"expected: <true> but was: <false>","expected":"true","actual":"false","stack":["com.example.VersionTest.ordersBySemVer(VersionTest.java:35)"]}
```

- `test` runs it again: `mvn test -Dtest=com.example.VersionTest#ordersBySemVer`.
- `at` is the first line of the project's own code in the stack, relative to its source folder.
- `stack` holds only the project's own frames, those of classes in a folder such as `target/classes`; frames of the JDK, JUnit, Surefire and other libraries, which come from JARs, are left out.
- `expected` and `actual` are there when the assertion gives them, `cause` is the innermost cause, and `output` is the end of what the test printed when `junit.platform.output.capture.stdout` or `.stderr` is `true`.

The last line counts the tests; when everything passes, it is the only line:

```json
{"v":1,"event":"summary","passed":2017,"failed":0,"aborted":97,"skipped":0,"ms":12573}
```

`failed` also counts a test class whose setup failed. The file is written anew by each test JVM: with Surefire's `forkCount` above 1 or `reuseForks=false`, it holds only the last one's tests. `aborted` counts tests whose assumption did not hold, `skipped` disabled tests. Within `"v":1`, fields are added, never renamed or removed.

## What it is meant to do

- **Stay warm**: one JVM per project that keeps the libraries loaded and compiled between runs, and loads the project's own classes fresh for every run.
- **Run what a change affects**: only the tests whose classes a change touches, the new ones and the ones that failed last time. When it cannot tell, it runs every test. CI still runs them all.
- **Report for agents**: one line when everything passes; for a failure, the file and line of the assertion, what was expected and what came, and the command that runs it again.
- **Report on every edit**: the affected tests run as soon as a file is saved, and the result reaches the agent with the edit, without a turn of its own.

## License

[MIT](LICENSE)
