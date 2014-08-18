// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildtool;

import com.google.common.base.Preconditions;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;
import com.google.devtools.build.lib.blaze.BlazeCommandEventHandler;
import com.google.devtools.build.lib.exec.ExecutionOptions;
import com.google.devtools.build.lib.pkgcache.LoadingPhaseRunner;
import com.google.devtools.build.lib.pkgcache.PackageCacheOptions;
import com.google.devtools.build.lib.util.OptionsUtils;
import com.google.devtools.build.lib.util.io.OutErr;
import com.google.devtools.build.lib.vfs.PathFragment;
import com.google.devtools.build.lib.view.BuildView;
import com.google.devtools.build.lib.view.TopLevelArtifactContext;
import com.google.devtools.build.lib.view.config.InvalidConfigurationException;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.Converters.RangeConverter;
import com.google.devtools.common.options.Option;
import com.google.devtools.common.options.OptionsBase;
import com.google.devtools.common.options.OptionsClassProvider;
import com.google.devtools.common.options.OptionsProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

/**
 * A BuildRequest represents a single invocation of the build tool by a user.
 * A request specifies a list of targets to be built for a single
 * configuration, a pair of output/error streams, and additional options such
 * as --keep_going, --jobs, etc.
 */
public class BuildRequest implements OptionsClassProvider {

  /**
   * Options interface--can be used to parse command-line arguments.
   *
   * See also ExecutionOptions; from the user's point of view, there's no
   * qualitative difference between these two sets of options.
   */
  public static class BuildRequestOptions extends OptionsBase {

    /* "Execution": options related to the execution of a build: */

    @Option(name = "jobs",
            abbrev = 'j',
            defaultValue = "200",
            category = "strategy",
            help = "The number of concurrent jobs to run. "
                + "0 means build sequentially. Values above " + MAX_JOBS
                + " are not allowed.")
    public int jobs;

    @Option(name = "progress_report_interval",
            defaultValue = "0",
            category = "verbosity",
            converter = ProgressReportIntervalConverter.class,
            help = "The number of seconds to wait between two reports on"
                + " still running jobs.  The default value 0 means to use"
                + " the default 10:30:60 incremental algorithm.")
    public int progressReportInterval;

    @Option(name = "show_builder_stats",
        defaultValue = "false",
        category = "verbosity",
        help = "If set, parallel builder will report worker-related statistics.")
    public boolean useBuilderStatistics;

    @Option(name = "explain",
            defaultValue = "null",
            category = "verbosity",
            converter = OptionsUtils.PathFragmentConverter.class,
            help = "Causes Blaze to explain each executed step of the build. "
            + "The explanation is written to the specified log file.")
    public PathFragment explanationPath;

    @Option(name = "verbose_explanations",
            defaultValue = "false",
            category = "verbosity",
            help = "Increases the verbosity of the explanations issued if --explain is enabled. "
            + "Has no effect if --explain is not enabled.")
    public boolean verboseExplanations;

    @Option(name = "dump_makefile",
            defaultValue = "false",
            category = "verbosity",
            help = "Causes Blaze to dump a representation of the action graph "
            + "in (pseudo) make syntax to the file 'Makefile.blaze'")
    public boolean dumpMakefile;

    @Option(name = "dump_action_graph",
            defaultValue = "false",
            category = "verbosity",
            help = "Causes Blaze to dump a representation of the action graph "
            + "in GraphViz syntax to the file 'BlazeActionGraph.dot'")
    public boolean dumpActionGraph;

    @Option(name = "dump_action_graph_for_package",
        allowMultiple = true,
        defaultValue = "",
        category = "verbosity",
        help = "Constrains what dump_action_graph outputs to these packages.")
    public List<String> dumpActionGraphForPackage = new ArrayList<>();

    @Option(name = "dump_action_graph_with_middlemen",
        defaultValue = "true",
        category = "verbosity",
        help = "Causes Blaze to dump a representation of the action graph "
        + "in GraphViz syntax to the file 'BlazeActionGraph.dot'")
    public boolean dumpActionGraphWithMiddlemen;

    @Deprecated
    @Option(name = "dump_providers",
        defaultValue = "false",
        category = "undocumented",
        help = "This is a no-op.")
    public boolean dumpProviders;

    @Option(name = "incremental_builder",
            defaultValue = "true",
            category = "strategy",
            help = "Enables an incremental builder aimed at faster "
            + "incremental builds. Currently it has the greatest effect on null"
            + "builds.")
    public boolean useIncrementalDependencyChecker;

    @Option(name = "dump_targets",
            converter = TargetsDumper.FormatType.Converter.class,
            defaultValue = "null",
            category = "verbosity",
            help = "If this option is set it causes Blaze to dump the targets required by "
                   + "the build to the file 'BlazeTargets.txt'."
                   + "Legal values are: 'inputs' (output the labels of the input files in "
                   + "alphabetical order), 'rules' (output the labels of the rules in "
                   + "alphabetical order), 'package' (output the packages of the input files "
                   + "and rules in alphabetical order. 'all' (output the labels of the "
                   + "input files and rules in alphabetical order.")
    public TargetsDumper.FormatType dumpTargets;

    @Option(name = "dump_host_deps",
        defaultValue = "true",
        category = "verbosity",
        help = "If true, the options --dump_targets also dumps host dependencies.")
    public boolean dumpHostDeps;

    @Option(name = "dump_to_stdout",
        defaultValue = "false",
        category = "verbosity",
        help = "Forces the options --dump_action_graph, --dump_makefile and --dump_inputs to "
               + "dump their output to stdout instead of a file.")
    public boolean dumpToStdout;

    @Option(name = "analyze",
            defaultValue = "true",
            category = "undocumented",
            help = "Execute the analysis phase; this is the usual behaviour. "
                + "Specifying --noanalyze causes the build to stop before starting the "
                + "analysis phase, returning zero iff the package loading completed "
                + "successfully; this mode is useful for testing.")
    public boolean performAnalysisPhase;

    @Option(name = "build",
            defaultValue = "true",
            category = "what",
            help = "Execute the build; this is the usual behaviour. "
            + "Specifying --nobuild causes the build to stop before executing the "
            + "build actions, returning zero iff the package loading and analysis "
            + "phases completed successfully; this mode is useful for testing "
            + "those phases.")
    public boolean performExecutionPhase;

    @Option(name = "compile_only",
        defaultValue = "false",
        category = "what",
        help = "If specified, Blaze will only build files that are generated by lightweight "
            + "compilation actions, skipping more expensive build steps (such as linking).")
    public boolean compileOnly;

    @Option(name = "compilation_prerequisites_only",
        defaultValue = "false",
        category = "what",
        help = "If specified, Blaze will only build files that are prerequisites to compilation "
             + "of the given target (for example, generated source files and headers) without "
             + "building the target itself. This flag is ignored if --compile_only is enabled.")
    public boolean compilationPrerequisitesOnly;

    @Option(name = "output_groups",
        converter = Converters.CommaSeparatedOptionListConverter.class,
        allowMultiple = true,
        defaultValue = "",
        category = "undocumented",
        help = "Specifies, which output groups of the top-level target to build.")
    public List<String> outputGroups;

    @Option(name = "show_result",
            defaultValue = "1",
            category = "verbosity",
            help = "Show the results of the build.  For each "
            + "target, state whether or not it was brought up-to-date, and if "
            + "so, a list of output files that were built.  The printed files "
            + "are convenient strings for copy+pasting to the shell, to "
            + "execute them.\n"
            + "This option requires an integer argument, which "
            + "is the threshold number of targets above which result "
            + "information is not printed. "
            + "Thus zero causes suppression of the message and MAX_INT "
            + "causes printing of the result to occur always.  The default is one.")
    public int maxResultTargets;

    @Option(name = "announce",
            defaultValue = "false",
            category = "verbosity",
            help = "Deprecated. No-op.",
            deprecationWarning = "This option is now deprecated and is a no-op")
    public boolean announce;

    @Option(name = "symlink_prefix",
        defaultValue = "blaze-",
        category = "misc",
        help = "The prefix that is prepended to any of the convenience symlinks that are created "
            + "after a build. If '/' is passed, then no symlinks are created (not even the "
            + "blaze-out and blaze-google3 symlinks), and no warning is emitted."
        )
    public String symlinkPrefix;

    @Option(name = "experimental_multi_cpu",
            converter = Converters.CommaSeparatedOptionListConverter.class,
            allowMultiple = true,
            defaultValue = "",
            category = "semantics",
            help = "This flag allows specifying multiple target CPUs. If this is specified, "
                + "the --cpu option is ignored.")
    public List<String> multiCpus;
  }

  /**
   * Converter for progress_report_interval: [0, 3600].
   */
  public static class ProgressReportIntervalConverter extends RangeConverter {
    public ProgressReportIntervalConverter() {
      super(0, 3600);
    }
  }

  private static final int MAX_JOBS = 2000;
  private static final int JOBS_TOO_HIGH_WARNING = 1000;

  private final UUID id;
  private final LoadingCache<Class<? extends OptionsBase>, OptionsBase> optionsCache;

  /** A human-readable description of all the non-default option settings. */
  private final String optionsDescription;

  /**
   * The name of the Blaze command that the user invoked.
   * Used for --announce.
   */
  private final String commandName;

  private final OutErr outErr;
  private final List<String> targets;

  private long startTimeMillis = 0; // milliseconds since UNIX epoch.

  private boolean runningInEmacs = false;
  private boolean runTests = false;

  private static final List<Class<? extends OptionsBase>> MANDATORY_OPTIONS = ImmutableList.of(
          BuildRequestOptions.class,
          PackageCacheOptions.class,
          LoadingPhaseRunner.Options.class,
          BuildView.Options.class,
          ExecutionOptions.class);

  private BuildRequest(String commandName,
                       final OptionsProvider options,
                       final OptionsProvider startupOptions,
                       List<String> targets,
                       OutErr outErr,
                       UUID id,
                       long startTimeMillis) {
    this.commandName = commandName;
    this.optionsDescription = OptionsUtils.asShellEscapedString(options);
    this.outErr = outErr;
    this.targets = targets;
    this.id = id;
    this.startTimeMillis = startTimeMillis;
    this.optionsCache = CacheBuilder.newBuilder()
        .build(new CacheLoader<Class<? extends OptionsBase>, OptionsBase>() {
          @Override
          public OptionsBase load(Class<? extends OptionsBase> key) throws Exception {
            OptionsBase result = options.getOptions(key);
            if (result == null) {
              result = startupOptions.getOptions(key);
            }

            return result;
          }
        });

    for (Class<? extends OptionsBase> optionsClass : MANDATORY_OPTIONS) {
      Preconditions.checkNotNull(getOptions(optionsClass));
    }
  }

  /**
   * Returns a unique identifier that universally identifies this build.
   */
  public UUID getId() {
    return id;
  }

  /**
   * Returns the name of the Blaze command that the user invoked.
   */
  public String getCommandName() {
    return commandName;
  }

  /**
   * Set to true if this build request was initiated by Emacs.
   * (Certain output formatting may be necessary.)
   */
  public void setRunningInEmacs() {
    runningInEmacs = true;
  }

  boolean isRunningInEmacs() {
    return runningInEmacs;
  }

  /**
   * Enables test execution for this build request.
   */
  public void setRunTests() {
    runTests = true;
  }

  /**
   * Returns true if tests should be run by the build tool.
   */
  public boolean shouldRunTests() {
    return runTests;
  }

  /**
   * Returns the (immutable) list of targets to build in commandline
   * form.
   */
  public List<String> getTargets() {
    return targets;
  }

  /**
   * Returns the output/error streams to which errors and progress messages
   * should be sent during the fulfillment of this request.
   */
  public OutErr getOutErr() {
    return outErr;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <T extends OptionsBase> T getOptions(Class<T> clazz) {
    try {
      return (T) optionsCache.get(clazz);
    } catch (ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Returns the set of command-line options specified for this request.
   */
  public BuildRequestOptions getBuildOptions() {
    return getOptions(BuildRequestOptions.class);
  }

  /**
   * Returns the set of options related to the loading phase.
   */
  public PackageCacheOptions getPackageCacheOptions() {
    return getOptions(PackageCacheOptions.class);
  }

  /**
   * Returns the set of options related to the loading phase.
   */
  public LoadingPhaseRunner.Options getLoadingOptions() {
    return getOptions(LoadingPhaseRunner.Options.class);
  }

  /**
   * Returns the set of command-line options related to the view specified for
   * this request.
   */
  public BuildView.Options getViewOptions() {
    return getOptions(BuildView.Options.class);
  }

  /**
   * Returns the human-readable description of the non-default options
   * for this build request.
   */
  public String getOptionsDescription() {
    return optionsDescription;
  }

  /**
   * Return the time (according to System.currentTimeMillis()) at which the
   * service of this request was started.
   */
  public long getStartTime() {
    return startTimeMillis;
  }

  /**
   * Validates the options for this BuildRequest.
   *
   * <p>Issues warnings or throws {@code InvalidConfigurationException} for option settings that
   * conflict.
   *
   * @return list of warnings
   */
  public List<String> validateOptions() throws InvalidConfigurationException {
    List<String> warnings = new ArrayList<>();
    // Validate "jobs".
    int jobs = getBuildOptions().jobs;
    if (jobs < 0 || jobs > MAX_JOBS) {
      throw new InvalidConfigurationException(String.format(
          "Invalid parameter for --jobs: %d. Only values 0 <= jobs <= %d are allowed.", jobs,
          MAX_JOBS));
    }
    if (jobs > JOBS_TOO_HIGH_WARNING) {
      warnings.add(
          String.format("High value for --jobs: %d. You may run into memory issues", jobs));
    }

    // Validate other BuildRequest options.
    if (getBuildOptions().verboseExplanations && getBuildOptions().explanationPath == null) {
      warnings.add("--verbose_explanations has no effect when --explain=<file> is not enabled");
    }
    if (getBuildOptions().compileOnly && getBuildOptions().compilationPrerequisitesOnly) {
      throw new InvalidConfigurationException(
          "--compile_only and --compilation_prerequisites_only are not compatible");
    }

    return warnings;
  }

  /** Creates a new TopLevelArtifactContext from this build request. */
  public TopLevelArtifactContext getTopLevelArtifactContext() {
    return new TopLevelArtifactContext(getCommandName(),
        getBuildOptions().compileOnly, getBuildOptions().compilationPrerequisitesOnly,
        getOptions(ExecutionOptions.class).testStrategy.equals("exclusive"),
        ImmutableSet.<String>copyOf(getBuildOptions().outputGroups));
  }

  public String getSymlinkPrefix() {
    return getBuildOptions().symlinkPrefix;
  }

  public ImmutableSortedSet<String> getMultiCpus() {
    return ImmutableSortedSet.copyOf(getBuildOptions().multiCpus);
  }

  public static BuildRequest create(String commandName, OptionsProvider options,
      OptionsProvider startupOptions,
      List<String> targets, OutErr outErr, UUID commandId, long commandStartTime) {

    BuildRequest request = new BuildRequest(commandName, options, startupOptions, targets, outErr,
        commandId, commandStartTime);

    // All this, just to pass a global boolean from the client to the server. :(
    if (options.getOptions(BlazeCommandEventHandler.Options.class).runningInEmacs) {
      request.setRunningInEmacs();
    }

    return request;
  }

}
