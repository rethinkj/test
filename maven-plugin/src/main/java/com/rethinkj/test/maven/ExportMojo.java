package com.rethinkj.test.maven;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.PluginParameterExpressionEvaluator;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.configurator.expression.ExpressionEvaluationException;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;

/**
 * Writes what the runner needs to know about a module to {@code
 * target/rethinkj-test/project.properties}: the test class path, the source, resource and class
 * folders, the Java release, the options and system properties Surefire gives the test JVM, and the JUnit
 * Platform Launcher that matches the module's JUnit.
 *
 * <p>The values are those Maven computes for this build, with its profiles, so the runner reads
 * them instead of reading POMs itself.
 */
@Mojo(name = "export", requiresDependencyResolution = ResolutionScope.TEST, threadSafe = true)
public class ExportMojo extends AbstractMojo {

  /** The version of the file's content; the runner exports anew when it reads another one. */
  static final String FORMAT = "1";

  /** Where the file goes, relative to the module's build folder. */
  static final String FILE = "rethinkj-test/project.properties";

  private static final String SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin";
  private static final String COMPILER = "org.apache.maven.plugins:maven-compiler-plugin";
  private static final String RESOURCES = "org.apache.maven.plugins:maven-resources-plugin";

  /** Surefire's late replacement, {@code @{name}}, by a property of the project. */
  private static final Pattern LATE = Pattern.compile("@\\{([^}]+)}");

  @Parameter(defaultValue = "${project}", readonly = true, required = true)
  private MavenProject project;

  @Parameter(defaultValue = "${session}", readonly = true, required = true)
  private MavenSession session;

  @Parameter(defaultValue = "${mojoExecution}", readonly = true, required = true)
  private MojoExecution execution;

  @Component
  private RepositorySystem repositories;

  @Override
  public void execute() throws MojoExecutionException {
    if ("pom".equals(project.getPackaging())) {
      return;
    }
    var values = new TreeMap<String, String>();
    values.put("format", FORMAT);
    try {
      values.put("classpath", join(project.getTestClasspathElements()));
      values.put("compileClasspath", join(project.getCompileClasspathElements()));
    } catch (DependencyResolutionRequiredException e) {
      throw new MojoExecutionException("Cannot resolve the test class path", e);
    }
    values.put("classes", project.getBuild().getOutputDirectory());
    values.put("testClasses", project.getBuild().getTestOutputDirectory());
    values.put("sources", join(project.getCompileSourceRoots()));
    values.put("testSources", join(project.getTestCompileSourceRoots()));
    values.put(
        "resources",
        join(project.getResources().stream().map(r -> r.getDirectory()).toList()));
    values.put(
        "testResources",
        join(project.getTestResources().stream().map(r -> r.getDirectory()).toList()));
    values.put("workdir", project.getBasedir().getPath());
    values.put("poms", join(poms()));
    var compiler = configuration(COMPILER, "default-compile");
    var testCompiler = configuration(COMPILER, "default-testCompile");
    put(values, "release", value(compiler, "release"), "${maven.compiler.release}");
    put(values, "testRelease", value(testCompiler, "testRelease"), "${maven.compiler.testRelease}");
    put(values, "parameters", value(compiler, "parameters"), "${maven.compiler.parameters}");
    put(
        values,
        "testParameters",
        value(testCompiler, "parameters"),
        "${maven.compiler.parameters}");
    if (processors(compiler) || processors(testCompiler)) {
      values.put("processors", "true");
    }
    // Resources are filtered with these files; a change to one has to copy them again.
    var filters = new ArrayList<>(project.getBuild().getFilters());
    for (var execution : List.of("default-resources", "default-testResources")) {
      var listed = list(configuration(RESOURCES, execution), "filters");
      if (listed != null) {
        filters.addAll(List.of(listed.strip().split("\\s*,\\s*")));
      }
    }
    values.put(
        "filters",
        join(filters.stream()
            .map(filter -> new File(filter).isAbsolute()
                ? filter
                : new File(project.getBasedir(), filter).getPath())
            .toList()));
    var surefire = configuration(SUREFIRE, "default-test");
    // Surefire enables Java's assertions unless told not to.
    var assertions = value(surefire, "enableAssertions");
    if (assertions == null) {
      assertions = evaluate("${enableAssertions}");
    }
    values.put("assertions", Boolean.toString(!"false".equals(assertions)));
    var argLine = value(surefire, "argLine");
    if (argLine == null) {
      argLine = evaluate("${argLine}");
    }
    if (argLine != null) {
      values.put("jvm.args", late(argLine));
    }
    // Which tests a run without -Dtest selects; unset, the runner uses Surefire's defaults.
    put(values, "test.includes", list(surefire, "includes"), null);
    put(values, "test.excludes", list(surefire, "excludes"), null);
    put(values, "test.groups", value(surefire, "groups"), "${groups}");
    put(values, "test.excludedGroups", value(surefire, "excludedGroups"), "${excludedGroups}");
    var properties = surefire == null ? null : surefire.getChild("systemPropertyVariables");
    if (properties != null) {
      for (var property : properties.getChildren()) {
        var text = evaluate(property.getValue());
        if (text != null) {
          values.put("system." + property.getName(), text);
        }
      }
    }
    // As Surefire does by default: properties given to Maven with -D win over the configuration.
    if (!"false".equals(value(surefire, "promoteUserPropertiesToSystemProperties"))) {
      session
          .getUserProperties()
          .forEach((name, value) -> values.put("system." + name, value.toString()));
    }
    values.put("launcher", launcher());
    write(values);
  }

  /**
   * Whether javac may run annotation processors in a compiler execution. When unsure it says yes,
   * so that the runner refuses the module rather than compile it differently.
   */
  private boolean processors(Xpp3Dom compiler) throws MojoExecutionException {
    var proc = value(compiler, "proc");
    if ("none".equals(proc)) {
      return false;
    }
    // Older compiler plugins ignore the property, so it only ever counts towards processors.
    var property = evaluate("${maven.compiler.proc}");
    if (processing(proc) || processing(property)) {
      return true;
    }
    return compiler != null
        && (entries(compiler, "annotationProcessorPaths")
            || entries(compiler, "annotationProcessors"));
  }

  private static boolean processing(String proc) {
    return "full".equals(proc) || "only".equals(proc);
  }

  private static boolean entries(Xpp3Dom configuration, String name) {
    var child = configuration.getChild(name);
    return child != null && child.getChildCount() > 0;
  }

  /** Puts a configured value, or else the value of a property, if there is one. */
  private void put(Map<String, String> values, String key, String configured, String property)
      throws MojoExecutionException {
    var value = configured != null || property == null ? configured : evaluate(property);
    if (value != null && !value.isBlank()) {
      values.put(key, value);
    }
  }

  /**
   * The values of a list parameter such as {@code <includes>}, separated by commas: its children,
   * or the value Maven splits at commas when it has none.
   */
  private String list(Xpp3Dom configuration, String name) throws MojoExecutionException {
    var child = configuration == null ? null : configuration.getChild(name);
    if (child == null) {
      return null;
    }
    if (child.getChildCount() == 0) {
      var text = evaluate(child.getValue());
      return text == null || text.isBlank() ? null : text;
    }
    var items = new ArrayList<String>();
    for (var item : child.getChildren()) {
      var text = evaluate(item.getValue());
      if (text != null && !text.isBlank()) {
        items.add(text);
      }
    }
    return String.join(",", items);
  }

  /** The POM of the module and those of its parents, whose changes call for a new export. */
  private List<String> poms() {
    var files = new ArrayList<String>();
    for (var module = project; module != null; module = module.getParent()) {
      if (module.getFile() != null) {
        files.add(module.getFile().getPath());
      }
    }
    return files;
  }

  /** The module's own JUnit Platform Launcher, or the one of the version of its JUnit Platform. */
  private String launcher() throws MojoExecutionException {
    String version = null;
    for (Artifact artifact : project.getArtifacts()) {
      if (artifact.getGroupId().equals("org.junit.platform")) {
        if (artifact.getArtifactId().equals("junit-platform-launcher")) {
          return artifact.getFile().getPath();
        }
        if (artifact.getArtifactId().equals("junit-platform-engine")) {
          version = artifact.getVersion();
        }
      }
    }
    if (version == null) {
      throw new MojoExecutionException(
          project.getId() + " does not test with the JUnit Platform: its test class path has no"
              + " junit-platform-engine");
    }
    var request = new ArtifactRequest(
        new DefaultArtifact("org.junit.platform", "junit-platform-launcher", "jar", version),
        project.getRemoteProjectRepositories(),
        null);
    try {
      return repositories
          .resolveArtifact(session.getRepositorySession(), request)
          .getArtifact()
          .getFile()
          .getPath();
    } catch (ArtifactResolutionException e) {
      throw new MojoExecutionException("Cannot resolve junit-platform-launcher " + version, e);
    }
  }

  /** A plugin's configuration, merged with that of one of its executions. */
  private Xpp3Dom configuration(String key, String executionId) {
    Plugin plugin = project.getPlugin(key);
    if (plugin == null) {
      return null;
    }
    var merged = (Xpp3Dom) plugin.getConfiguration();
    var specific = plugin.getExecutionsAsMap().get(executionId);
    if (specific != null && specific.getConfiguration() != null) {
      merged = Xpp3Dom.mergeXpp3Dom(new Xpp3Dom((Xpp3Dom) specific.getConfiguration()), merged);
    }
    return merged;
  }

  private String value(Xpp3Dom configuration, String name) throws MojoExecutionException {
    var child = configuration == null ? null : configuration.getChild(name);
    return child == null ? null : evaluate(child.getValue());
  }

  /** A value as the plugin gets it, with Maven's {@code ${…}} replaced; null when unset. */
  private String evaluate(String text) throws MojoExecutionException {
    if (text == null) {
      return null;
    }
    try {
      var value = new PluginParameterExpressionEvaluator(session, execution).evaluate(text);
      return value == null ? null : value.toString().strip();
    } catch (ExpressionEvaluationException e) {
      throw new MojoExecutionException("Cannot evaluate " + text, e);
    }
  }

  /**
   * Replaces Surefire's late properties, {@code @{…}}, in an {@code argLine}, as Surefire does
   * when the tests start. The export runs no lifecycle phase, so a property that a goal of one sets,
   * such as JaCoCo's {@code prepare-agent} in {@code initialize}, is empty here: the runner's JVM
   * starts without what that goal adds.
   */
  private String late(String argLine) {
    Matcher late = LATE.matcher(argLine);
    var result = new StringBuilder();
    while (late.find()) {
      var name = late.group(1);
      var replacement = session
          .getUserProperties()
          .getProperty(name, project.getProperties().getProperty(name, ""));
      late.appendReplacement(result, Matcher.quoteReplacement(replacement));
    }
    late.appendTail(result);
    return result.toString().strip();
  }

  private void write(Map<String, String> values) throws MojoExecutionException {
    var file = Path.of(project.getBuild().getDirectory(), FILE);
    try {
      Files.createDirectories(file.getParent());
      try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
        // Properties.store would add a date, so that every export changed the file.
        for (var entry : values.entrySet()) {
          out.write(escape(entry.getKey()) + "=" + escape(entry.getValue()) + "\n");
        }
      }
    } catch (IOException e) {
      throw new MojoExecutionException("Cannot write " + file, e);
    }
    getLog().info("Wrote " + file);
  }

  /** Escapes a key or value as {@link java.util.Properties#load(java.io.Reader)} reads it. */
  static String escape(String text) {
    var out = new StringBuilder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        case '=', ':', '#', '!' -> out.append('\\').append(c);
        case ' ' -> out.append(i == 0 ? "\\ " : " ");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  private static String join(List<String> paths) {
    return String.join(File.pathSeparator, paths);
  }
}
