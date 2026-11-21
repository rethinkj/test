package com.rethinkj.test;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records which of a module's classes run while a test class runs.
 *
 * <p>{@link Impact} gives each of the module's classes a field of its own, as JaCoCo does, and puts
 * a check of it at the start of every method: when it is not yet set, the method sets it and calls
 * {@link #hit}. So a method that runs again costs a read of the field. Starting a new record clears
 * the fields of the classes that set theirs. An interface cannot have such a field; its methods
 * call {@link #hit} every time.
 *
 * <p>A static initializer runs once per run, during the first test class that uses its class. What
 * it calls is recorded for the class too, so that a later test class that uses the class depends
 * on it as well. While one runs, every method calls {@link #hit}.
 *
 * <p>A static field written outside a static initializer, as a cache that a test class fills, holds
 * what ran while it was written. A later test class that uses its class depends on all that ran in
 * the test class that wrote it.
 *
 * <p>Public because the classes that call it are defined by another class loader.
 */
public final class Probe {

  /** The name of the field {@link Impact} adds. */
  static final String FIELD = "$rethinkjHit";

  /** Whether a static initializer of the module runs, so that every method reports itself. */
  public static boolean initializing;

  private static final Set<Class<?>> HITS = new LinkedHashSet<>();
  private static final ArrayDeque<String> INITIALIZERS = new ArrayDeque<>();

  /** For each class of this run, what its static initializer ran. */
  private static final Map<String, Set<String>> INITIALIZED = new HashMap<>();

  /** The classes whose static fields were written in the current record. */
  private static final Set<String> WRITTEN = ConcurrentHashMap.newKeySet();

  /** For each class of this run, what ran in the records in which its static fields were written. */
  private static final Map<String, Set<String>> WRITERS = new HashMap<>();

  private Probe() {}

  /** Called by a method of the module's class given the first time it runs in a record. */
  public static synchronized void hit(Class<?> type) {
    HITS.add(type);
    for (var initializer : INITIALIZERS) {
      if (!initializer.equals(type.getName())) {
        INITIALIZED.computeIfAbsent(initializer, name -> new LinkedHashSet<>()).add(type.getName());
      }
    }
  }

  /** Called before a static field of the class given is written, outside its initializer. */
  public static void wrote(Class<?> owner) {
    WRITTEN.add(owner.getName());
  }

  /** Called as the static initializer of the class given starts. */
  public static synchronized void enter(Class<?> type) {
    INITIALIZERS.push(type.getName());
    initializing = true;
  }

  /** Called as the static initializer of the class given returns. */
  public static synchronized void exit(Class<?> type) {
    INITIALIZERS.remove(type.getName());
    initializing = !INITIALIZERS.isEmpty();
  }

  /** Starts a new record, and returns the names of the classes of the one before. */
  static synchronized List<String> restart() {
    var names = new ArrayList<String>(HITS.size());
    try {
      for (var type : HITS) {
        names.add(type.getName());
        if (!type.isInterface()) {
          reset(type);
        }
      }
    } finally {
      HITS.clear();
    }
    for (var owner : WRITTEN) {
      WRITERS.computeIfAbsent(owner, name -> new LinkedHashSet<>()).addAll(names);
    }
    WRITTEN.clear();
    return names;
  }

  private static void reset(Class<?> type) {
    try {
      Field field = type.getDeclaredField(FIELD);
      field.setAccessible(true);
      // The class ran a method, so it is initialized: setting the field runs nothing.
      field.setBoolean(null, false);
    } catch (LinkageError e) {
      // Its static initializer failed: the class cannot run again in this run, so its field does
      // not matter.
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("No probe in " + type, e);
    }
  }

  /** What ran for each class of this run before a test class could use it: in its static
   * initializer, and in the records that wrote its static fields.
   */
  static synchronized Map<String, Set<String>> initialized() {
    var all = new HashMap<String, Set<String>>();
    INITIALIZED.forEach(
        (name, ran) -> all.computeIfAbsent(name, key -> new LinkedHashSet<>()).addAll(ran));
    WRITERS.forEach(
        (name, ran) -> all.computeIfAbsent(name, key -> new LinkedHashSet<>()).addAll(ran));
    return all;
  }

  /** Forgets everything of a run, whose classes then go with its class loader. */
  static synchronized void end() {
    restart();
    INITIALIZED.clear();
    INITIALIZERS.clear();
    WRITERS.clear();
    initializing = false;
  }
}
