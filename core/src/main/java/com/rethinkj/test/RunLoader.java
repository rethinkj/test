package com.rethinkj.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;

/**
 * The class loader of one run: the module's test classes and classes, loaded fresh, in front of
 * the libraries and JUnit, which stay loaded. It defines the module's classes with a call of
 * {@link Probe} in every method, so that {@link Impact} learns which classes each test class ran.
 */
final class RunLoader extends URLClassLoader {

  private final Impact impact;

  RunLoader(String name, URL[] folders, ClassLoader parent, Impact impact) {
    super(name, folders, parent);
    this.impact = impact;
  }

  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    var path = name.replace('.', '/') + ".class";
    var file = findResource(path);
    if (file == null) {
      throw new ClassNotFoundException(name);
    }
    byte[] bytes;
    try (var in = file.openStream()) {
      bytes = in.readAllBytes();
    } catch (IOException e) {
      throw new ClassNotFoundException(name, e);
    }
    var probed = impact.probe(name, bytes, this);
    // The folder as the code source, as Maven's class loader gives it: some tests find files by it.
    var source = new CodeSource(folder(file, path), (CodeSigner[]) null);
    return defineClass(name, probed, 0, probed.length, source);
  }

  // Maven's order: test classes, classes, libraries. A test resource wins over a main one of the
  // same name, and both over one in a library.
  @Override
  public URL getResource(String name) {
    var own = findResource(name);
    return own != null ? own : super.getResource(name);
  }

  @Override
  public Enumeration<URL> getResources(String name) throws IOException {
    var all = new ArrayList<>(Collections.list(findResources(name)));
    all.addAll(Collections.list(getParent().getResources(name)));
    return Collections.enumeration(all);
  }

  private static URL folder(URL file, String path) {
    var text = file.toString();
    try {
      return new URL(text.substring(0, text.length() - path.length()));
    } catch (MalformedURLException e) {
      throw new UncheckedIOException(e);
    }
  }
}
