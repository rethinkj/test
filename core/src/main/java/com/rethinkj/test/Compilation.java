package com.rethinkj.test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.stream.Stream;
import java.util.zip.CRC32C;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * Brings a module's class folders up to date with its sources, with javac in this JVM.
 *
 * <p>It compiles only the source files that changed since the last time. When that changes what
 * other classes compile against (a signature, a constant, a supertype), or a source file was
 * deleted, it compiles all sources of the set again, and the tests too when the main classes
 * changed so. Changed resources are copied by the build tool's own goals, which filter them as
 * {@code mvn test} does.
 */
final class Compilation {

  /** A compile error, with the file relative to the module. */
  record Error(String file, long line, String message) {}

  /**
   * What an update did.
   *
   * @param compiled how many source files were compiled
   * @param errors the compile errors; when there are some, the classes are not up to date
   * @param anew whether everything was compiled and copied anew, as after a new export
   * @param resourcesCopied whether resources were copied, because they changed or anew
   */
  record Result(int compiled, List<Error> errors, boolean anew, boolean resourcesCopied) {}

  private static final Path STATE = Path.of("target", "rethinkj-test", "sources.properties");

  private final Build build;
  private final JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
  private final StandardJavaFileManager files;
  private final Runnable copyResources;

  /**
   * @param copyResources copies the module's resources into its class folders
   */
  Compilation(Build build, Runnable copyResources) {
    this.build = build;
    this.copyResources = copyResources;
    this.files = javac.getStandardFileManager(null, null, StandardCharsets.UTF_8);
  }

  Result update() {
    if (build.processors()) {
      throw new IllegalStateException(
          "The module runs annotation processors, which the runner does not yet: use mvn test");
    }
    var state = build.workdir().resolve(STATE);
    var known = load(state);
    // Another export, a library installed anew, or classes another build wrote: compile all.
    var settings = settings();
    if (!settings.equals(known.get("settings")) || !outputs().equals(known.get("outputs"))) {
      known.clear();
    }
    boolean initialized = !known.isEmpty();
    var main = stamps(build.sources(), ".java");
    var test = stamps(build.testSources(), ".java");
    var errors = new ArrayList<Error>();
    var now = new TreeMap<String, String>();

    var mainSet = new SourceSet(
        build.sources(),
        build.classes(),
        build.compileClassPath(),
        build.release(),
        build.parameters());
    var mainChange = mainSet.update(main, initialized, select(known, "main:"), errors);
    int compiled = mainChange.compiled;
    if (errors.isEmpty()) {
      put(now, "main:", main);
      var testSet = new SourceSet(
          build.testSources(),
          build.testClasses(),
          testClassPath(),
          build.testRelease(),
          build.testParameters());
      boolean testsKnown = initialized && !mainChange.apiChanged;
      var testChange = testSet.update(test, testsKnown, select(known, "test:"), errors);
      compiled += testChange.compiled;
      put(now, "test:", test);
    }

    var resources = stamps(build.resources(), "");
    // Copied also after a new export, which can change how they are filtered.
    boolean copied =
        !select(known, "res:").equals(resources) || (!initialized && !resources.isEmpty());
    if (copied) {
      copyResources.run();
    }
    // After errors nothing is recorded: javac may have written some classes, so the next update
    // compiles all of them.
    if (errors.isEmpty()) {
      put(now, "res:", resources);
      now.put("settings", settings);
      now.put("outputs", outputs());
    } else {
      now.clear();
    }
    save(state, now);
    return new Result(compiled, errors, !initialized, copied);
  }

  /**
   * The export and the libraries the classes were compiled with. Every export counts, also one
   * that wrote the same file: a change to a POM can change how resources are filtered.
   */
  private String settings() {
    try {
      var exported =
          Files.exists(build.file()) ? Files.getLastModifiedTime(build.file()).toMillis() : 0;
      return exported + "-" + build.libraryStamp();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * The files in the module's class folders, by name, size and time: classes and the copied
   * resources, so that one another build changed or deleted is noticed.
   */
  private String outputs() {
    var crc = new CRC32C();
    for (var folder : List.of(build.classes(), build.testClasses())) {
      if (!Files.isDirectory(folder)) {
        continue;
      }
      try (var walk = Files.walk(folder)) {
        for (var file : walk.filter(Files::isRegularFile).sorted().toList()) {
          var stamp = file + ":" + Files.size(file) + ":"
              + Files.getLastModifiedTime(file).toMillis();
          crc.update(stamp.getBytes(StandardCharsets.UTF_8));
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return Long.toHexString(crc.getValue());
  }

  /** The class path the tests compile against, in Maven's order: test classes first. */
  private List<Path> testClassPath() {
    var path = new ArrayList<>(List.of(build.testClasses(), build.classes()));
    path.addAll(build.libraries());
    return path;
  }

  /**
   * @param compiled how many source files were compiled
   * @param apiChanged whether what other classes compile against may have changed
   */
  private record Change(int compiled, boolean apiChanged) {}

  /** The sources of one class folder: the main ones or the tests. */
  private final class SourceSet {

    private final List<Path> roots;
    private final Path output;
    private final List<Path> classPath;
    private final String release;
    private final boolean parameters;

    SourceSet(
        List<Path> roots, Path output, List<Path> classPath, String release, boolean parameters) {
      this.roots = roots;
      this.output = output;
      this.classPath = classPath;
      this.release = release;
      this.parameters = parameters;
    }

    /**
     * @param known whether {@code before} holds what was compiled; if not, all is compiled
     */
    Change update(
        Map<String, String> sources,
        boolean known,
        Map<String, String> before,
        List<Error> errors) {
      var changed = sources.entrySet().stream()
          .filter(source -> !source.getValue().equals(before.get(source.getKey())))
          .map(source -> Path.of(source.getKey()))
          .toList();
      boolean deleted = before.keySet().stream().anyMatch(source -> !sources.containsKey(source));
      if (!known || deleted) {
        return all(sources, errors);
      }
      if (changed.isEmpty()) {
        return new Change(0, false);
      }
      var old = api(changed);
      // Deleted first, so that a class the source no longer declares does not seem unchanged.
      try {
        for (var file : old.keySet()) {
          Files.delete(file);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      compile(changed, errors);
      if (!errors.isEmpty()) {
        // javac may have checked the changed files against classes that are out of date; only
        // compiling all of them tells whether the errors are real.
        errors.clear();
        return all(sources, errors);
      }
      var now = api(changed);
      // Also a new class: it can hide a class of the same name that another file imports.
      return old.equals(now) ? new Change(changed.size(), false) : all(sources, errors);
    }

    private Change all(Map<String, String> sources, List<Error> errors) {
      try (Stream<Path> stale = Files.exists(output) ? Files.walk(output) : Stream.empty()) {
        for (var file : stale.filter(file -> file.toString().endsWith(".class")).toList()) {
          Files.delete(file);
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      var everything = sources.keySet().stream().map(Path::of).toList();
      compile(everything, errors);
      return new Change(everything.size(), true);
    }

    private void compile(List<Path> sources, List<Error> errors) {
      if (sources.isEmpty()) {
        return;
      }
      try {
        Files.createDirectories(output);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      var options = new ArrayList<>(List.of(
          "-d",
          output.toString(),
          "-cp",
          String.join(File.pathSeparator, classPath.stream().map(Path::toString).toList()),
          "-g",
          "-nowarn",
          "-implicit:none",
          // shortcut: UTF-8 like our own projects; export project.build.sourceEncoding when one is
          // not.
          "-encoding",
          "UTF-8"));
      if (release != null) {
        options.addAll(List.of("--release", release));
      }
      if (parameters) {
        options.add("-parameters");
      }
      var diagnostics = new DiagnosticCollector<JavaFileObject>();
      var units = files.getJavaFileObjectsFromPaths(sources);
      javac.getTask(null, files, diagnostics, options, null, units).call();
      for (var diagnostic : diagnostics.getDiagnostics()) {
        if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
          var source = diagnostic.getSource();
          var file = source == null
              ? ""
              : build.workdir().relativize(Path.of(source.toUri())).toString().replace('\\', '/');
          errors.add(
              new Error(file, diagnostic.getLineNumber(), diagnostic.getMessage(Locale.ROOT)));
        }
      }
    }

    /**
     * What other classes compile against, of every class the given source files compile to, by the
     * class file.
     */
    private Map<Path, String> api(List<Path> sources) {
      var result = new HashMap<Path, String>();
      for (var source : sources) {
        var folder = output.resolve(packageOf(source));
        if (!Files.isDirectory(folder)) {
          continue;
        }
        try (var classes = Files.list(folder)) {
          for (var file :
              classes.filter(file -> file.toString().endsWith(".class")).toList()) {
            var model = ClassFile.of().parse(Files.readAllBytes(file));
            var sourceFile = model.findAttribute(Attributes.sourceFile());
            if (sourceFile.isPresent()
                && sourceFile
                    .get()
                    .sourceFile()
                    .stringValue()
                    .equals(source.getFileName().toString())) {
              result.put(file, signature(model));
            }
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
      return result;
    }

    private Path packageOf(Path source) {
      for (var root : roots) {
        if (source.startsWith(root)) {
          var parent = root.relativize(source).getParent();
          return parent == null ? Path.of("") : parent;
        }
      }
      return Path.of("");
    }
  }

  /** The part of a class that others compile against: everything but its private members and code. */
  static String signature(ClassModel type) {
    var text = new StringBuilder();
    text.append(type.flags().flagsMask()).append(' ').append(type.thisClass().asInternalName());
    type.superclass().ifPresent(parent -> text.append(" extends ").append(parent.asInternalName()));
    type.interfaces()
        .forEach(parent -> text.append(" implements ").append(parent.asInternalName()));
    type.findAttribute(Attributes.signature())
        .ifPresent(signature ->
            text.append(" <").append(signature.signature().stringValue()).append('>'));
    type.findAttribute(Attributes.permittedSubclasses())
        .ifPresent(permitted -> permitted
            .permittedSubclasses()
            .forEach(sub -> text.append(" permits ").append(sub.asInternalName())));
    // An annotation's retention and targets decide how the classes that use it are compiled.
    if ((type.flags().flagsMask() & ClassFile.ACC_ANNOTATION) != 0) {
      type.findAttribute(Attributes.runtimeVisibleAnnotations())
          .ifPresent(annotations -> annotations
              .annotations()
              .forEach(annotation -> text.append(" @").append(annotation)));
    }
    // The access of nested classes, which their own class files do not hold.
    type.findAttribute(Attributes.innerClasses())
        .ifPresent(inner -> inner
            .classes()
            .forEach(nested -> text.append(" nested ")
                .append(nested.innerClass().asInternalName())
                .append(' ')
                .append(nested.flagsMask())));
    type.findAttribute(Attributes.record())
        .ifPresent(record -> record
            .components()
            .forEach(component -> text.append(" component ")
                .append(component.name().stringValue())
                .append(component.descriptor().stringValue())));
    for (var field : type.fields()) {
      if ((field.flags().flagsMask() & ClassFile.ACC_PRIVATE) == 0) {
        text.append("\nfield ")
            .append(field.flags().flagsMask())
            .append(' ')
            .append(field.fieldName().stringValue())
            .append(field.fieldType().stringValue());
        field
            .findAttribute(Attributes.signature())
            .ifPresent(signature ->
                text.append(" <").append(signature.signature().stringValue()).append('>'));
        // javac copies constants into the classes that use them.
        field
            .findAttribute(Attributes.constantValue())
            .ifPresent(constant -> text.append(" = ").append(constant.constant().constantValue()));
      }
    }
    for (var method : type.methods()) {
      if ((method.flags().flagsMask() & ClassFile.ACC_PRIVATE) == 0) {
        text.append("\nmethod ")
            .append(method.flags().flagsMask())
            .append(' ')
            .append(method.methodName().stringValue())
            .append(method.methodType().stringValue());
        method
            .findAttribute(Attributes.signature())
            .ifPresent(signature ->
                text.append(" <").append(signature.signature().stringValue()).append('>'));
        // An annotation's defaults decide which of its uses compile.
        method
            .findAttribute(Attributes.annotationDefault())
            .ifPresent(value -> text.append(" default ").append(value.defaultValue()));
        method
            .findAttribute(Attributes.exceptions())
            .ifPresent(exceptions -> exceptions
                .exceptions()
                .forEach(exception -> text.append(" throws ").append(exception.asInternalName())));
      }
    }
    return text.toString();
  }

  /** For each file under the folders with the suffix given, a checksum of its content. */
  private static Map<String, String> stamps(List<Path> roots, String suffix) {
    var stamps = new TreeMap<String, String>();
    for (var root : roots) {
      if (!Files.exists(root)) {
        continue;
      }
      try (var walk = Files.walk(root)) {
        for (var file : walk.filter(Files::isRegularFile).toList()) {
          var name = file.getFileName().toString();
          // Compiled for the class path, a module descriptor has no place in it.
          if (name.endsWith(suffix) && !name.equals("module-info.java")) {
            // The content, not the time: an agent can save a file twice within a tick of the clock.
            var crc = new CRC32C();
            crc.update(Files.readAllBytes(file));
            stamps.put(file.toString(), Long.toHexString(crc.getValue()));
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return stamps;
  }

  private static Map<String, String> select(Map<String, String> state, String prefix) {
    var selected = new LinkedHashMap<String, String>();
    state.forEach((key, value) -> {
      if (key.startsWith(prefix)) {
        selected.put(key.substring(prefix.length()), value);
      }
    });
    return selected;
  }

  private static void put(Map<String, String> state, String prefix, Map<String, String> stamps) {
    stamps.forEach((file, stamp) -> state.put(prefix + file, stamp));
  }

  private static Map<String, String> load(Path file) {
    var state = new TreeMap<String, String>();
    if (!Files.exists(file)) {
      return state;
    }
    var values = new Properties();
    try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      values.load(in);
    } catch (IOException e) {
      return state;
    }
    for (var key : values.stringPropertyNames()) {
      state.put(key, values.getProperty(key));
    }
    return state;
  }

  private static void save(Path file, Map<String, String> state) {
    var values = new Properties();
    values.putAll(state);
    try {
      Files.createDirectories(file.getParent());
      try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
        values.store(out, null);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
