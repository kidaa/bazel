#!/bin/bash

# Copyright 2014 Google Inc. All rights reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# This script bootstraps building a Bazel binary without Bazel then
# use this compiled Bazel to bootstrap Bazel itself. It can also
# be provided with a previous version of Bazel to bootstrap Bazel
# itself.
# The resulting binary can be found at output/bazel.

set -o errexit

cd "$(dirname "$0")"

source scripts/bootstrap/buildenv.sh

function usage() {
  [ -n "${1:-build}" ] && echo "Invalid command(s): $1" >&2
  echo "syntax: $0 [command[,command]* [BAZEL_BIN [BAZEL_SUM]]]" >&2
  echo "  General purpose commands:" >&2
  echo "     build       = compile,tools,init (default)" >&2
  echo "     compile     = compile a Bazel binary for usage" >&2
  echo "     tools       = compile and install tooling for Bazel" >&2
  echo "     init        = initialize the base workspace" >&2
  echo "  Commands for developers:" >&2
  echo "     all         = build,determinism,test" >&2
  echo "     determinism = test for stability of Bazel builds" >&2
  echo "     test        = run the full test suite of Bazel" >&2
  exit 1
}

function parse_options() {
  local keywords="(build|compile|tools|init|all|determinism|bootstrap|test)"
  COMMANDS="${1:-build}"
  [[ "${COMMANDS}" =~ ^$keywords(,$keywords)*$ ]] || usage "$@"
  DO_COMPILE=
  DO_TOOLS_COMPILATION=
  DO_CHECKSUM=
  DO_FULL_CHECKSUM=1
  DO_TESTS=
  DO_BASE_WORKSPACE_INIT=
  [[ "${COMMANDS}" =~ (compile|build|all) ]] && DO_COMPILE=1
  [[ "${COMMANDS}" =~ (tools|build|all) ]] && DO_TOOLS_COMPILATION=1
  [[ "${COMMANDS}" =~ (init|build|all) ]] && DO_BASE_WORKSPACE_INIT=1
  [[ "${COMMANDS}" =~ (bootstrap|determinism|all) ]] && DO_CHECKSUM=1
  [[ "${COMMANDS}" =~ (bootstrap) ]] && DO_FULL_CHECKSUM=
  [[ "${COMMANDS}" =~ (test|all) ]] && DO_TESTS=1

  BAZEL_BIN=${2:-"bazel-bin/src/bazel"}
  BAZEL_SUM=${3:-"x"}
}

parse_options "${@}"

mkdir -p output
: ${BAZEL:=${2-}}

#
# Create an initial binary so we can host ourself
#
if [ ! -x "${BAZEL}" ]; then
  display "$INFO You can skip this first step by providing a path to the bazel binary as second argument:"
  display "$INFO    $0 ${COMMANDS} /path/to/bazel"
  new_step 'Building Bazel from scratch'
  source scripts/bootstrap/compile.sh
  cp ${OUTPUT_DIR}/bazel output/bazel
  BAZEL=$(pwd)/output/bazel
fi

#
# Bootstrap bazel using the previous bazel binary = release binary
#
if [ "${EMBED_LABEL-x}" = "x" ]; then
  # Add a default label when unspecified
  git_sha1=$(git_sha1)
  EMBED_LABEL="head (@${git_sha1:-non-git})"
fi

source scripts/bootstrap/bootstrap.sh
if [ $DO_COMPILE ]; then
  new_step 'Building Bazel with Bazel'
  display "."
  bazel_bootstrap //src:bazel output/bazel 0755 1
  BAZEL=$(pwd)/output/bazel
fi

#
# Bootstrap tools using the release binary
#
if [ $DO_TOOLS_COMPILATION ]; then
  new_step 'Building Bazel tools'
  bazel_bootstrap //third_party/ijar:ijar tools/jdk/ijar 0755
  bazel_bootstrap //src/java_tools/singlejar:SingleJar_deploy.jar \
      tools/jdk/SingleJar_deploy.jar
  bazel_bootstrap //src/java_tools/buildjar:JavaBuilder_deploy.jar \
      tools/jdk/JavaBuilder_deploy.jar
  bazel_bootstrap //src/java_tools/buildjar/java/com/google/devtools/build/buildjar/genclass:GenClass_deploy.jar \
      tools/jdk/GenClass_deploy.jar
  if [[ $PLATFORM == "darwin" ]]; then
    bazel_bootstrap //src/tools/xcode-common/java/com/google/devtools/build/xcode/actoolzip:actoolzip_deploy.jar \
        tools/objc/precomp_actoolzip_deploy.jar
    bazel_bootstrap //src/tools/xcode/ibtoolwrapper:ibtoolwrapper tools/objc/ibtoolwrapper 0755
    bazel_bootstrap //src/tools/xcode-common/java/com/google/devtools/build/xcode/swiftstdlibtoolzip:swiftstdlibtoolzip_deploy.jar \
        tools/objc/precomp_swiftstdlibtoolzip_deploy.jar
    bazel_bootstrap //src/objc_tools/momczip:momczip_deploy.jar \
        tools/objc/precomp_momczip_deploy.jar
    bazel_bootstrap //src/objc_tools/bundlemerge:bundlemerge_deploy.jar \
        tools/objc/precomp_bundlemerge_deploy.jar
    bazel_bootstrap //src/objc_tools/plmerge:plmerge_deploy.jar \
        tools/objc/precomp_plmerge_deploy.jar
    bazel_bootstrap //src/objc_tools/xcodegen:xcodegen_deploy.jar \
        tools/objc/precomp_xcodegen_deploy.jar
    bazel_bootstrap //src/tools/xcode/stdredirect:StdRedirect.dylib \
        tools/objc/StdRedirect.dylib 0755
    bazel_bootstrap //src/tools/xcode/realpath:realpath tools/objc/realpath 0755
  fi
fi

#
# Output is deterministic between two bootstrapped bazel binary using the actual tools and the
# released binary.
#
if [ $DO_CHECKSUM ]; then
  new_step "Determinism test"
  if [ ! -f ${BAZEL_SUM:-x} ]; then
    BAZEL_SUM=bazel-out/bazel_checksum
    log "First build"
    bootstrap_test ${BAZEL} ${BAZEL_SUM}
  else
    BOOTSTRAP=${BAZEL}
  fi
  if [ "${BAZEL_SUM}" != "${OUTPUT_DIR}/bazel_checksum" ]; then
    cp ${BAZEL_SUM} ${OUTPUT_DIR}/bazel_checksum
  fi
  if [ $DO_FULL_CHECKSUM ]; then
    log "Second build"
    bootstrap_test ${BOOTSTRAP} bazel-out/bazel_checksum
    log "Comparing output"
    (diff -U 0 ${OUTPUT_DIR}/bazel_checksum bazel-out/bazel_checksum >&2) \
        || fail "Differences detected in outputs!"
  fi
fi

#
# Tests
#
if [ $DO_TESTS ]; then
  new_step "Running tests"
  display "."
  [ -n "$JAVAC_VERSION" ] || get_java_version
  if [[ ! "${BAZEL_TEST_FILTERS-}" =~ "-jdk8" ]] \
      && [ "8" -gt ${JAVAC_VERSION#*.} ]; then
    display "$WARNING Your version of Java is lower than 1.8!"
    display "$WARNING Deactivating Java 8 tests, please use a JDK 8 to fully"
    display "$WARNING test Bazel."
    if [ -n "${BAZEL_TEST_FILTERS-}" ]; then
      BAZEL_TEST_FILTERS="${BAZEL_TEST_FILTERS},-jdk8"
    else
      BAZEL_TEST_FILTERS="-jdk8"
    fi
  fi
  $BAZEL --bazelrc=${BAZELRC} --nomaster_bazelrc test \
      --test_tag_filters="${BAZEL_TEST_FILTERS-}" \
      --build_tests_only \
      --javacopt="-source ${JAVA_VERSION} -target ${JAVA_VERSION}" \
      -k --test_output=errors //src/... //third_party/ijar/... //scripts/... \
      || fail "Tests failed"
fi

#
# Setup the base workspace
#
if [ $DO_BASE_WORKSPACE_INIT ]; then
  new_step 'Setting up base workspace'
  display "."
  source scripts/bootstrap/init_workspace.sh
fi

clear_log
display "Build successful! Binary is here: ${BAZEL}"
