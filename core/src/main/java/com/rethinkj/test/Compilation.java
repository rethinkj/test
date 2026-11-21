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
   */
  record Result(int compiled, List<Error> errors) {}

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
    var state = build.workdir().resolve(STATE);
    var known = load(state);
    var now = new TreeMap<String, String>();
    var main = stamps(build.sources(), ".java");
    var test = stamps(build.testSources(), ".java");
    var errors = new ArrayList<Error>();

    var mainSet = new SourceSet(build.sources(), build.classes(), classPath(false));
    var mainChange = mainSet.update(main, select(known, "main:"), errors);
    remember(now, "main:", main, known, mainChange, errors.isEmpty());
    int compiled = mainChange.compiled;
    if (errors.isEmpty()) {
      var testSet = new SourceSet(build.testSources(), build.testClasses(), classPath(true));
      var before = mainChange.apiChanged ? Map.<String, String>of() : select(known, "test:");
      var testChange = testSet.update(test, before, errors);
      remember(now, "test:", test, known, testChange, errors.isEmpty());
      compiled += testChange.compiled;
    } else {
      put(now, "test:", select(known, "test:"));
    }

    var resources = stamps(build.resources(), "");
    if (!select(known, "res:").equals(resources)) {
      copyResources.run();
    }
    put(now, "res:", resources);
    save(state, now);
    return new Result(compiled, errors);
  }

  /**
   * Records the sources of a set as compiled. After errors it keeps what was known before, so that
   * the next update compiles the same files again, or, when it had to compile all, nothing.
   */
  private static void remember(
      Map<String, String> now,
      String prefix,
      Map<String, String> sources,
      Map<String, String> known,
      Change change,
      boolean compiled) {
    if (compiled) {
      put(now, prefix, sources);
    } else if (!change.all) {
      put(now, prefix, select(known, prefix));
    }
  }

  /** The class path a set compiles against: the libraries and the module's class folders. */
  private List<Path> classPath(boolean tests) {
    var path = new ArrayList<>(build.libraries());
    path.add(build.classes());
    if (tests) {
      path.add(build.testClasses());
    }
    return path;
  }

  /**
   * @param compiled how many source files were compiled
   * @param apiChanged whether what other classes compile against may have changed
   * @param all whether all sources of the set were compiled
   */
  private record Change(int compiled, boolean apiChanged, boolean all) {}

  /** The sources of one class folder: the main ones or the tests. */
  private final class SourceSet {

    private final List<Path> roots;
    private final Path output;
    private final List<Path> classPath;

    SourceSet(List<Path> roots, Path output, List<Path> classPath) {
      this.roots = roots;
      this.output = output;
      this.classPath = classPath;
    }

    Change update(Map<String, String> sources, Map<String, String> before, List<Error> errors) {
      var changed = sources.entrySet().stream()
          .filter(source -> !source.getValue().equals(before.get(source.getKey())))
          .map(source -> Path.of(source.getKey()))
          .toList();
      boolean deleted = before.keySet().stream().anyMatch(source -> !sources.containsKey(source));
      if (before.isEmpty() || deleted) {
        return all(sources, errors);
      }
      if (changed.isEmpty()) {
        return new Change(0, false, false);
      }
      var old = api(changed);
      compile(changed, errors);
      if (!errors.isEmpty()) {
        return new Change(changed.size(), false, false);
      }
      var now = api(changed);
      // A new class changes nothing others were compiled against; a changed or removed one may.
      boolean same =
          old.entrySet().stream().allMatch(type -> type.getValue().equals(now.get(type.getKey())));
      return same ? new Change(changed.size(), false, false) : all(sources, errors);
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
      return new Change(everything.size(), true, true);
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
      if (build.release() != null) {
        options.addAll(List.of("--release", build.release()));
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

    /** What other classes compile against, of every class the given source files compile to. */
    private Map<String, String> api(List<Path> sources) {
      var result = new HashMap<String, String>();
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
              result.put(model.thisClass().asInternalName(), signature(model));
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
      if (!Files.isDirectory(root)) {
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
