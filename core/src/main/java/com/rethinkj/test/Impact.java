package com.rethinkj.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.lang.classfile.ClassTransform;
import java.lang.classfile.CodeBuilder;
import java.lang.classfile.CodeElement;
import java.lang.classfile.CodeTransform;
import java.lang.classfile.MethodModel;
import java.lang.classfile.MethodTransform;
import java.lang.classfile.Opcode;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.instruction.FieldInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.ClassDesc;
import java.lang.constant.ConstantDescs;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.zip.CRC32C;

/**
 * Which test classes a change can affect. For each test class it keeps the module's classes the
 * class used when it last ran, each with a hash of its bytecode then: the classes whose methods
 * ran, as {@link Probe} saw them, what ran in their static initializers and in the test classes
 * that wrote their static fields, and the classes all these name, which a test can use without a
 * call. A test class is affected when one of these classes has another hash now or is gone, when
 * it is new, or when it failed the last time.
 *
 * <p>The hash leaves out debug information, so that an edit of comments or blank lines affects
 * nothing. A constant that javac copied into a class changes that class's bytecode.
 */
final class Impact {

  private static final Path FILE = Path.of("target", "rethinkj-test", "impact.properties");
  private static final ClassDesc PROBE = ClassDesc.of(Probe.class.getName());
  private static final MethodTypeDesc HIT =
      MethodTypeDesc.of(ConstantDescs.CD_void, ConstantDescs.CD_Class);

  private final Build build;
  private final Map<String, Set<String>> references = new HashMap<>();
  private final Map<String, List<String>> supertypes = new HashMap<>();
  private final Map<Path, String[]> hashes = new HashMap<>();

  /** For each test class, the classes it used and their hashes then. */
  private final Map<String, Map<String, String>> used = new TreeMap<>();

  private final Set<String> failed = new TreeSet<>();

  /** The build and runner the records were made with; another one makes them worthless. */
  private String stamp;

  Impact(Build build) {
    this.build = build;
    load();
  }

  /** A class file with a check of {@link Probe} at the start of every method. */
  byte[] probe(String name, byte[] bytes, ClassLoader loader) {
    var resolver = ClassHierarchyResolver.ofResourceParsing(loader)
        .orElse(ClassHierarchyResolver.defaultResolver());
    var files = ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
    var model = files.parse(bytes);
    // Every class it names: a test can use a class without a call, by a static field, a class
    // literal, instanceof or a cast.
    var named = new HashSet<String>();
    for (var entry : model.constantPool()) {
      if (entry instanceof ClassEntry type) {
        // An array's element type: [Lcom/example/Cart; or [[I.
        var internal = type.asInternalName().replaceFirst("^\\[+L?", "").replaceFirst(";$", "");
        named.add(internal.replace('/', '.'));
      }
    }
    var parents = new ArrayList<String>();
    model.superclass().ifPresent(parent -> parents.add(parent.asInternalName().replace('/', '.')));
    model.interfaces().forEach(parent -> parents.add(parent.asInternalName().replace('/', '.')));
    synchronized (this) {
      references.put(name, named);
      supertypes.put(name, parents);
    }
    var self = model.thisClass().asSymbol();
    boolean flag = (model.flags().flagsMask() & ClassFile.ACC_INTERFACE) == 0;
    CodeTransform clinit = new CodeTransform() {
      @Override
      public void atStart(CodeBuilder code) {
        code.ldc(self).invokestatic(PROBE, "enter", HIT);
        check(code, self, flag);
      }

      @Override
      public void accept(CodeBuilder code, CodeElement element) {
        if (element instanceof ReturnInstruction) {
          code.ldc(self).invokestatic(PROBE, "exit", HIT);
        }
        // A static field of another class, which a later test class can read.
        if (element instanceof FieldInstruction field
            && field.opcode() == Opcode.PUTSTATIC
            && !field.owner().asSymbol().equals(self)) {
          code.ldc(field.owner().asSymbol()).invokestatic(PROBE, "wrote", HIT);
        }
        code.with(element);
      }
    };
    CodeTransform method = new CodeTransform() {
      @Override
      public void atStart(CodeBuilder code) {
        check(code, self, flag);
      }

      @Override
      public void accept(CodeBuilder code, CodeElement element) {
        if (element instanceof FieldInstruction field && field.opcode() == Opcode.PUTSTATIC) {
          code.ldc(field.owner().asSymbol()).invokestatic(PROBE, "wrote", HIT);
        }
        code.with(element);
      }
    };
    ClassTransform transform = (type, element) -> {
      if (element instanceof MethodModel each && each.code().isPresent()) {
        boolean initializer = each.methodName().equalsString("<clinit>");
        type.transformMethod(each, MethodTransform.transformingCode(initializer ? clinit : method));
      } else {
        type.with(element);
      }
    };
    if (flag) {
      // Private, static and transient, so that serialization and most reflection ignore it.
      transform = transform.andThen(ClassTransform.endHandler(type -> type.withField(
          Probe.FIELD,
          ConstantDescs.CD_boolean,
          ClassFile.ACC_PRIVATE
              | ClassFile.ACC_STATIC
              | ClassFile.ACC_TRANSIENT
              | ClassFile.ACC_SYNTHETIC)));
    }
    return files.transformClass(model, transform);
  }

  /**
   * The check at the start of a method: {@code if (!hit || Probe.initializing) { hit = true;
   * Probe.hit(Self.class); }}, or the call alone in an interface.
   */
  private static void check(CodeBuilder code, ClassDesc self, boolean flag) {
    if (!flag) {
      code.ldc(self).invokestatic(PROBE, "hit", HIT);
      return;
    }
    var call = code.newLabel();
    var skip = code.newLabel();
    code.getstatic(self, Probe.FIELD, ConstantDescs.CD_boolean)
        .ifeq(call)
        .getstatic(PROBE, "initializing", ConstantDescs.CD_boolean)
        .ifeq(skip)
        .labelBinding(call)
        .iconst_1()
        .putstatic(self, Probe.FIELD, ConstantDescs.CD_boolean)
        .ldc(self)
        .invokestatic(PROBE, "hit", HIT)
        .labelBinding(skip);
  }

  /** Whether there are records made with the build and runner given. */
  boolean usable(String stamp) {
    if (!stamp.equals(this.stamp)) {
      used.clear();
      failed.clear();
      this.stamp = stamp;
    }
    return !used.isEmpty();
  }

  /** Forgets the record of a test class, which then runs the next time. */
  synchronized void drop(String testClass) {
    used.remove(testClass);
  }

  /** Forgets all records, as after a change whose effect on the tests nobody can tell. */
  synchronized void forget() {
    used.clear();
    failed.clear();
  }

  /**
   * The test classes among those given that a change since their last run can affect, each with
   * the reason.
   */
  synchronized Map<String, String> affected(Set<String> testClasses) {
    var affected = new TreeMap<String, String>();
    for (var test : testClasses) {
      var classes = used.get(test);
      if (classes == null) {
        affected.put(test, "new");
      } else if (failed.contains(test)) {
        affected.put(test, "failed");
      } else {
        for (var entry : classes.entrySet()) {
          if (!entry.getValue().equals(hash(entry.getKey()))) {
            affected.put(test, entry.getKey());
            break;
          }
        }
      }
    }
    return affected;
  }

  /**
   * Records what a test class used while it ran: the class itself, the classes the probes saw
   * run, those that ran in the static initializers of these, and the classes all these name.
   *
   * @param initialized what the static initializers of this run ran, by class
   */
  synchronized void ran(
      String testClass, List<String> hits, Map<String, Set<String>> initialized, boolean passed) {
    var classes = new LinkedHashSet<String>();
    classes.add(testClass);
    var pending = new ArrayDeque<>(hits);
    var done = new HashSet<String>();
    while (!pending.isEmpty()) {
      var name = pending.poll();
      if (!done.add(name)) {
        continue;
      }
      classes.add(name);
      pending.addAll(initialized.getOrDefault(name, Set.of()));
      for (var named : references.getOrDefault(name, Set.of())) {
        // With its supertypes: a static field named through a class can be declared in one.
        for (var type = named; type != null; type = superclass(type)) {
          classes.add(type);
          // A class it names was maybe initialized by another test class, running other classes.
          pending.addAll(initialized.getOrDefault(type, Set.of()));
          for (var parent : supertypes.getOrDefault(type, List.of())) {
            classes.add(parent);
            pending.addAll(initialized.getOrDefault(parent, Set.of()));
          }
        }
      }
    }
    var record = new TreeMap<String, String>();
    for (var name : classes) {
      var hash = hash(name);
      // Only the module's own classes: the libraries' change restarts the daemon.
      if (!hash.equals("gone")) {
        record.put(name, hash);
      }
    }
    used.put(testClass, record);
    if (passed) {
      failed.remove(testClass);
    } else {
      failed.add(testClass);
    }
  }

  private String superclass(String name) {
    var parents = supertypes.getOrDefault(name, List.of());
    return parents.isEmpty() ? null : parents.getFirst();
  }

  /** The hash of a class of the module now, without debug information, or {@code gone}. */
  String hash(String name) {
    var relative = name.replace('.', '/') + ".class";
    for (var folder : List.of(build.testClasses(), build.classes())) {
      var file = folder.resolve(relative);
      if (Files.isRegularFile(file)) {
        return hash(file);
      }
    }
    return "gone";
  }

  private String hash(Path file) {
    try {
      // Keyed by the bytes: a class compiled anew can keep its size and time.
      var bytes = Files.readAllBytes(file);
      var raw = new CRC32C();
      raw.update(bytes);
      var stamp = Long.toHexString(raw.getValue());
      var known = hashes.get(file);
      if (known != null && known[0].equals(stamp)) {
        return known[1];
      }
      var files = ClassFile.of(
          ClassFile.DebugElementsOption.DROP_DEBUG,
          ClassFile.LineNumbersOption.DROP_LINE_NUMBERS,
          // A new constant pool: the old one keeps entries of what was dropped.
          ClassFile.ConstantPoolSharingOption.NEW_POOL,
          // Stack maps follow from the code; making them anew would need the class hierarchy.
          ClassFile.StackMapsOption.DROP_STACK_MAPS);
      var model = files.parse(bytes);
      var stripped = files.transformClass(
          model,
          ClassTransform.dropping(
              element -> element instanceof java.lang.classfile.attribute.SourceFileAttribute));
      var crc = new CRC32C();
      crc.update(stripped);
      var hash = Long.toHexString(crc.getValue());
      hashes.put(file, new String[] {stamp, hash});
      return hash;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  synchronized void save() {
    var values = new Properties();
    values.setProperty("stamp", stamp == null ? "" : stamp);
    values.setProperty("failed", String.join(",", failed));
    used.forEach((test, classes) -> {
      var text = new StringBuilder();
      classes.forEach((name, hash) -> text.append(name).append('=').append(hash).append(','));
      values.setProperty("used." + test, text.toString());
    });
    var file = build.workdir().resolve(FILE);
    try {
      Files.createDirectories(file.getParent());
      try (var out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
        values.store(out, null);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Reads the records again, as another JVM of the module may have written them since: one run
   * with {@code --fresh}.
   */
  synchronized void load() {
    used.clear();
    failed.clear();
    stamp = null;
    var file = build.workdir().resolve(FILE);
    if (!Files.exists(file)) {
      return;
    }
    var values = new Properties();
    try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      values.load(in);
    } catch (IOException e) {
      return;
    }
    stamp = values.getProperty("stamp");
    for (var test : values.getProperty("failed", "").split(",")) {
      if (!test.isEmpty()) {
        failed.add(test);
      }
    }
    for (var key : values.stringPropertyNames()) {
      if (key.startsWith("used.")) {
        var classes = new TreeMap<String, String>();
        for (var pair : values.getProperty(key).split(",")) {
          var equals = pair.indexOf('=');
          if (equals > 0) {
            classes.put(pair.substring(0, equals), pair.substring(equals + 1));
          }
        }
        used.put(key.substring("used.".length()), classes);
      }
    }
  }
}
