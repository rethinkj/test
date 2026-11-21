# rethinkj Test

A fast test runner for Java, built for the loop of a coding agent: edit, test, read, edit again. It keeps a warm JVM between runs, runs only the tests a change can affect, and reports a failure in a few lines an agent reads at once. It runs the tests people already write for JUnit Jupiter.

- Repository: https://github.com/rethinkj/test
- License: MIT

This file holds the rules for working on the repository, and nothing else. What the runner does is in the code, its Javadoc and the README. Do not describe features, findings, measurements or your own work here; the file changes only when a rule changes.

## Targets

- Windows 11 or later and Linux, on x64, with JDK 25.
- Tests are JUnit Jupiter tests, run by the stock Jupiter engine through the JUnit Platform Launcher. No test API, annotations or assertions of our own: what the runner adds comes as a Platform listener or a Jupiter extension, so a test runs the same with `mvn test`, in an IDE and here.
- Maven projects without Spring first. Gradle and Spring are later targets; nothing may rule them out. What the runner needs from a build (class path, test class folders, JVM options) comes from one adapter per build tool, never from Maven's folder layout read in the runner itself.
- `core` depends on `java.base`, `java.compiler` and the JUnit Platform Launcher only, at run time. Another library is added only with a reason in its pull request. An adapter depends on its build tool's API, which the build tool provides at run time, and on nothing else.

## Modules

| Directory | Artifact | Purpose |
| --- | --- | --- |
| `core/` | `rethinkj-test-core` | The runner: the warm JVM, the selection of tests and the report, package `com.rethinkj.test` |
| `maven-plugin/` | `rethinkj-test-maven-plugin` | The adapter for Maven: writes what the runner needs to know about a module, package `com.rethinkj.test.maven` |

A module is added to this table in the pull request that adds it.

## Build

Requires JDK 25 and Maven 3.9+.

```sh
mvn verify                        # build and test everything
mvn -pl core -am verify           # one module plus the modules it depends on
mvn spotless:apply                # format; mvn verify fails on a file that is not formatted
```

## Tests

- Keep tests few and fast. A test earns its place by failing when behavior a user can notice breaks. No benchmarks and no timing printouts in the test suite, no tests of tests, and no second test for what another already covers.
- The runner is tested on small projects under `core/src/test/resources/projects`, each a folder a user could build with `mvn test`. A test compares the runner's verdict with what Maven reports for the same project.
- The Maven plugin is tested by running it with Maven on the projects under `maven-plugin/src/it`; each checks what the goal wrote in its `verify.groovy`. `mvn verify -Dinvoker.skip` leaves them out.
- Benchmarks are not part of `mvn verify`. Their numbers go into the issue they were run for, never into the repository.

## Rules the code does not show

- Code, comments, messages and docs are in American English: color, behavior, initialize.
- A run never reports a test as passed that it did not run. It says how many tests it selected, ran and left out, and why it left them out. When it cannot tell what a change affects (a build file, a resource it does not track, a new version of the runner), it runs every test.
- A warm run gives the verdict a fresh `mvn test` gives. A test that passes in one and fails in the other is a bug of the runner, not of the test. A test class that changes what the JVM shares (system properties, default locale or time zone, `System.out`, static state of a library) runs in a fresh JVM.
- The report an agent reads is versioned. Within a version a field is added, never renamed, removed or given a new meaning.
- The selection of tests saves time in the loop; it never replaces the full run. Documentation and examples say that CI runs every test.
- Shared build configuration belongs in the root `pom.xml`. The version is changed with `mvn versions:set -DnewVersion=<version>`.

## Documentation

- Until there is a site in `docs/`, the README is the documentation, for people and agents. Keep it short and current: a change that users notice updates it in the same pull request.
- What changed goes into the changelog (below). Plans, status, findings and reports go into the issue tracker, never into the repository: no `PLAN.md`, `SUMMARY.md`, `VALIDATION.md`, benchmark results or review notes, and nothing of the kind in this file.

## Changes

- Nobody pushes to `main`. Work on a branch from `origin/main`, push it and open a pull request on GitHub. It is merged by squash once its checks pass.
- One pull request per topic, small enough to review. Its description says what changed and why, in a few sentences.
- Commit messages and pull request titles follow [Conventional Commits](https://www.conventionalcommits.org/): `<type>(<scope>): <summary>`, for example `feat(core): reuse the JVM between runs`. The scope is the module when the change stays in one. A breaking change gets `!` before the colon. The subject has at most 72 characters; a body only when the diff does not show why.
- A change that users of the runner can notice (new or changed command, option or report field, changed behavior, a fix, a raised requirement) adds `changelog/unreleased/<YYYY-MM-DD>-<slug>.md` with one top-level bullet written for those users. These files are folded into `CHANGELOG.md` at release.

## This repo is public

Everything pushed stays public in history. Before committing, run `git diff --cached | grep -nE '/home/|/Users/|/private/var/|\.ts\.net|ses_[A-Za-z0-9]{20}'` and keep these out:

- secrets, tokens and credential file contents;
- home folder and temp paths, machine names and tailnet hostnames. Write repo-relative paths, and use `host.example.ts.net` in tests;
- session IDs, chat or Slack links, and quotes from private sessions, chats or calls;
- issue numbers, links and suggested branch names from a private issue tracker;
- teammates' names attached to their plans, opinions or internal discussions, and private repos or branches;
- quotes of, or citations to, internal guides and rules. Say what the code does and why on its own terms;
- agent handoff notes, raw evidence, logs and benchmark dumps.
