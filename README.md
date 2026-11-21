<p align="center">
  <img src="assets/logo.svg" alt="rethinkj" width="96">
</p>

# rethinkj Test

A fast test runner for Java, built for the loop of a coding agent: edit, test, read, edit again. It runs the JUnit Jupiter tests a project already has, unchanged.

## Status

The report for agents works, with `mvn test` and with the warm runner, which keeps a JVM per module between runs and runs only the test classes a change can affect. With a hook it reports on every edit an agent makes. It targets Maven projects on Windows 11 and Linux, with JDK 25; Gradle and Spring come later. The version is `0.1.0-SNAPSHOT` and is not published yet: `mvn install` puts it into the local repository.

## Warm runner

Install it with `mvn install` in this repository, then run it in the folder of a Maven module:

```sh
runner=~/.m2/repository/com/rethinkj/test/rethinkj-test-core/0.1.0-SNAPSHOT/rethinkj-test-core-0.1.0-SNAPSHOT.jar
java -jar $runner                        # the test classes a change can affect
java -jar $runner --all                  # all tests of the module
java -jar $runner com.example.CartTest   # a class, a.BTest#method or a package
java -jar $runner --fresh                # once, in a new JVM
```

The first run asks Maven for the module's class path and test JVM options, compiles the module, and starts a JVM that stays running. After that, a run compiles only the files that changed and runs the tests in that JVM. A change that other classes compile against, such as a signature or a constant, compiles the module again. Changed resources are copied with Maven's `resources` goals. A change to a POM starts a new JVM.

In rethinkj/ui, an edit to one class of `core` plus a run of its test takes 0.1 to 0.2 s this way. With `mvn test -Dtest=…` it takes 6.6 to 9.3 s.

Without a selector it runs only the test classes that a change since their last run can affect. While a class runs, the runner records which of the module's classes run and which classes these name. It also records what ran in the static initializers of these classes, and in the test classes that wrote their static fields, as a cache that one test class fills for the next. A class is affected when one of the recorded classes now has other bytecode, or is gone. Comments and blank lines do not count. New test classes and those that failed the last time run too. All test classes run on the first run, after a new export or library, after a change to a resource, with parallel execution and with `--all`. A line before the report says how many test classes run, how many classes and tests are left out, and why, for example `{"v":1,"event":"selection","classes":3,"leftOut":93,"leftOutTests":512,"why":["changed com.example.Cart"]}`. Test classes that share caches depend on much of each other, so in a module like rethinkj/ui a change to a central class still runs most of them. CI should still run all tests, with `mvn test`.

It prints the report below. Two more kinds of lines can appear: `{"v":1,"event":"compile-error","at":"src/main/java/com/example/Cart.java:12","message":"…"}` when the code does not compile, and `{"v":1,"event":"error",…}` when the run could not start. It exits with 0 when every test passed, 1 when one failed and 2 for those two cases.

The JVM ends after 30 minutes without a run, and it is replaced when the classes of earlier runs do not leave memory. Its output goes to `target/rethinkj-test/daemon.log`.

Limits for now: tests run on the class path, also in a module with a `module-info.java`; sources are read as UTF-8; other modules of a multi-module build come from their JARs, as with `mvn -pl`; a module with annotation processors is refused; and a library that reads a resource through its own class finds its own copy before one of the module's tests.

## Report on every edit

With a hook, Claude Code gets the result of the affected tests after each edit, without a turn of its own to run them. Add this to `.claude/settings.json` of the project, with the path to the runner's JAR:

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write|MultiEdit",
        "hooks": [
          {"type": "command", "command": "java -jar \"<path to rethinkj-test-core.jar>\" --hook", "timeout": 120}
        ]
      }
    ]
  }
}
```

After an edit of a file in a module's `src`, the hook runs what a run without selectors runs, in that module. When the tests pass it says nothing. When they fail or the code does not compile, Claude Code gets the report's lines with the edit, the way it gets a compile error. The hook never fails the edit itself.

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
