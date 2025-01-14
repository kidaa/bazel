// Copyright 2015 Google Inc. All rights reserved.
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
package com.google.devtools.build.lib.rules.android;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.devtools.build.lib.Constants;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.Fragment;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.LabelConverter;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.StrictDepsConverter;
import com.google.devtools.build.lib.analysis.config.BuildConfiguration.StrictDepsMode;
import com.google.devtools.build.lib.analysis.config.BuildOptions;
import com.google.devtools.build.lib.analysis.config.ConfigurationEnvironment;
import com.google.devtools.build.lib.analysis.config.ConfigurationFragmentFactory;
import com.google.devtools.build.lib.analysis.config.FragmentOptions;
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException;
import com.google.devtools.build.lib.packages.Attribute.SplitTransition;
import com.google.devtools.build.lib.syntax.Label;
import com.google.devtools.common.options.Converters;
import com.google.devtools.common.options.EnumConverter;
import com.google.devtools.common.options.Option;

import java.util.List;

/**
 * Configuration fragment for Android rules.
 */
public class AndroidConfiguration extends BuildConfiguration.Fragment {
  /**
   * Value used to avoid multiple configurations from conflicting.
   *
   * <p>This is set to {@code ANDROID} in Android configurations and to {@code MAIN} otherwise. This
   * influences the output directory name: if it didn't, an Android and a non-Android configuration
   * would conflict if they had the same toolchain identifier.
   *
   * <p>Note that this is not just a theoretical concern: even if {@code --crosstool_top} and
   * {@code --android_crosstool_top} point to different labels, they may end up being redirected to
   * the same thing, and this is exactly what happens on OSX X.
   */
  public enum ConfigurationDistinguisher {
    MAIN(null),
    ANDROID("android");

    private final String suffix;

    private ConfigurationDistinguisher(String suffix) {
      this.suffix = suffix;
    };
  }

  /**
   * Converter for {@link com.google.devtools.build.lib.rules.android.AndroidConfiguration.ConfigurationDistinguisher}
   */
  public static final class ConfigurationDistinguisherConverter
      extends EnumConverter<ConfigurationDistinguisher> {
    public ConfigurationDistinguisherConverter() {
      super(ConfigurationDistinguisher.class, "Android configuration distinguisher");
    }
  }

  /**
   * Android configuration options.
   */
  public static class Options extends FragmentOptions {
    // Spaces make it impossible to specify this on the command line
    @Option(name = "Android configuration distinguisher",
        defaultValue = "MAIN",
        converter = ConfigurationDistinguisherConverter.class,
        category = "undocumented")
    public ConfigurationDistinguisher configurationDistinguisher;

    @Option(name = "android_crosstool_top",
        defaultValue = "null",
        category = "semantics",
        converter = LabelConverter.class,
        help = "The location of the C++ compiler used for Android builds.")
    public Label androidCrosstoolTop;

    @Option(name = "android_cpu",
        defaultValue = "armeabi",
        category = "semantics",
        help = "The Android target CPU.")
    public String cpu;

    @Option(name = "strict_android_deps",
        allowMultiple = false,
        defaultValue = "default",
        converter = StrictDepsConverter.class,
        category = "semantics",
        help = "If true, checks that an Android target explicitly declares all directly used "
            + "targets as dependencies.")
    public StrictDepsMode strictDeps;

    // Label of filegroup combining all Android tools used as implicit dependencies of
    // android_* rules
    @Option(name = "android_sdk",
            defaultValue = "null",
            category = "version",
            converter = LabelConverter.class,
            help = "Specifies Android SDK/platform that is used to build Android applications.")
    public Label sdk;

    @Option(name = "proguard_top",
        defaultValue = "null",
        category = "version",
        converter = LabelConverter.class,
        help = "Specifies which version of ProGuard to use for code removal when building an "
            + "Android binary.")
    public Label proguard;

    @Option(name = "legacy_android_native_support",
        defaultValue = "true",
        category = "semantics",
        help = "Switches back to old native support for android_binaries. Disable to link together "
            + "native deps of android_binaries into a single .so by default.")
    public boolean legacyNativeSupport;

    // TODO(bazel-team): Maybe merge this with --android_cpu above.
    @Option(name = "fat_apk_cpu",
            converter = Converters.CommaSeparatedOptionListConverter.class,
            allowMultiple = true,
            defaultValue = "",
            category = "undocumented",
            help = "Setting this option enables fat APKs, which contain native binaries for all "
                + "specified target architectures, e.g., --fat_apk_cpu=x86,armeabi-v7a. Note that "
                + "you will also at least need to select an Android-compatible crosstool. "
                + "If this flag is specified, then --android_cpu is ignored for dependencies of "
                + "android_binary rules.")
    public List<String> fatApkCpus;

    @Option(name = "experimental_android_use_jack_for_dexing",
        defaultValue = "false",
        category = "semantics",
        help = "Switches to the Jack and Jill toolchain for dexing instead of javac and dx.")
    public boolean useJackForDexing;

    @Option(name = "experimental_android_jack_sanity_checks",
        defaultValue = "false",
        category = "semantics",
        help = "Enables sanity checks for Jack and Jill compilation.")
    public boolean jackSanityChecks;

    @Override
    public void addAllLabels(Multimap<String, Label> labelMap) {
      if (proguard != null) {
        labelMap.put("android_proguard", proguard);
      }

      if (realAndroidCrosstoolTop() != null) {
        labelMap.put("android_crosstool_top", realAndroidCrosstoolTop());
      }

      labelMap.put("android_sdk", realSdk());
    }

    // This method is here because Constants.ANDROID_DEFAULT_SDK cannot be a constant, because we
    // replace the class file in the .jar after compilation. However, that means that we cannot use
    // it as an attribute value in an annotation.
    private Label realSdk() {
      return sdk == null
          ? Label.parseAbsoluteUnchecked(Constants.ANDROID_DEFAULT_SDK)
          : sdk;
    }

    // This method is here because Constants.ANDROID_DEFAULT_CROSSTOOL cannot be a constant, because
    // we replace the class file in the .jar after compilation. However, that means that we cannot
    // use it as an attribute value in an annotation.
    public Label realAndroidCrosstoolTop() {
      if (androidCrosstoolTop != null) {
        return androidCrosstoolTop;
      }

      if (Constants.ANDROID_DEFAULT_CROSSTOOL.equals("null")) {
        return null;
      }

      return Label.parseAbsoluteUnchecked(Constants.ANDROID_DEFAULT_CROSSTOOL);
    }

    @Override
    public ImmutableList<String> getDefaultsRules() {
      return ImmutableList.of("android_tools_defaults_jar(name = 'android_jar')");
    }

    @Override
    public List<SplitTransition<BuildOptions>> getPotentialSplitTransitions() {
      return ImmutableList.of(AndroidRuleClasses.ANDROID_SPLIT_TRANSITION);
    }
  }

  /**
   * Configuration loader for the Android fragment.
   */
  public static class Loader implements ConfigurationFragmentFactory {
    @Override
    public Fragment create(ConfigurationEnvironment env, BuildOptions buildOptions)
        throws InvalidConfigurationException {
      return new AndroidConfiguration(buildOptions.get(Options.class));
    }

    @Override
    public Class<? extends Fragment> creates() {
      return AndroidConfiguration.class;
    }

    @Override
    public ImmutableSet<Class<? extends FragmentOptions>> requiredOptions() {
      return ImmutableSet.<Class<? extends FragmentOptions>>of(Options.class);
    }
  }

  private final Label sdk;
  private final StrictDepsMode strictDeps;
  private final boolean legacyNativeSupport;
  private final String cpu;
  private final boolean fatApk;
  private final ConfigurationDistinguisher configurationDistinguisher;
  private final Label proguard;
  private final boolean useJackForDexing;
  private final boolean jackSanityChecks;

  AndroidConfiguration(Options options) {
    this.sdk = options.realSdk();
    this.strictDeps = options.strictDeps;
    this.legacyNativeSupport = options.legacyNativeSupport;
    this.cpu = options.cpu;
    this.fatApk = !options.fatApkCpus.isEmpty();
    this.configurationDistinguisher = options.configurationDistinguisher;
    this.proguard = options.proguard;
    this.useJackForDexing = options.useJackForDexing;
    this.jackSanityChecks = options.jackSanityChecks;
  }

  public String getCpu() {
    return cpu;
  }

  public Label getSdk() {
    return sdk;
  }

  public boolean getLegacyNativeSupport() {
    return legacyNativeSupport;
  }

  public StrictDepsMode getStrictDeps() {
    return strictDeps;
  }

  public boolean isFatApk() {
    return fatApk;
  }

  /**
   * Returns true if Jack should be used in place of javac/dx for Android compilation.
   */
  public boolean isJackUsedForDexing() {
    return useJackForDexing;
  }

  /**
   * Returns true if Jack sanity checks should be enabled. Only relevant if isJackUsedForDexing()
   * also returns true.
   */
  public boolean isJackSanityChecked() {
    return jackSanityChecks;
  }

  @Override
  public void addGlobalMakeVariables(ImmutableMap.Builder<String, String> globalMakeEnvBuilder) {
    globalMakeEnvBuilder.put("ANDROID_CPU", cpu);
  }

  @Override
  public String getOutputDirectoryName() {
    return configurationDistinguisher.suffix;
  }

  public Label getProguardLabel() {
    return proguard;
  }
}
