load("@build_bazel_rules_nodejs//:index.bzl", "nodejs_binary")

package(default_visibility = ["//visibility:public"])

# By default, rollup_bundle rule uses rollup from @npm workspace
# and it expects that all plugins are installed in the same workspace.
# This rule defines another rollup-bin from @tools_npm workspace.
# Usage: rollup_bundle(rollup_bin = "//tools/node_tools:rollup-bin, ...)
nodejs_binary(
    name = "rollup-bin",
    # Define only minimal required dependencies.
    # Otherwise remote build execution fails with the too many
    # files error when it builds :release target.
    data = [
        "@tools_npm//@rollup/plugin-terser",
        "@tools_npm//rollup",
    ],
    # The entry point must be "@tools_npm:node_modules/rollup/dist/bin/rollup",
    # But bazel doesn't run it correctly with the following command line:
    # bazel test --test_env=GERRIT_NOTEDB=ON --spawn_strategy=standalone \
    #    --genrule_strategy=standalone --test_output errors --test_summary detailed \
    #    --flaky_test_attempts 3 --test_verbose_timeout_warnings --build_tests_only \
    #    --subcommands //...
    # This command line appears in Gerrit CI.
    # For details, see comment in rollup-runner.js file
    entry_point = "//tools/node_tools:rollup-runner.js",
)

# Create a tsc_wrapped compiler rule to use in the ts_library
# compiler attribute when using self-managed dependencies
# TODO: Would be nice to just use `tsc-bin` below instead.
# We would prefer to not depend on @bazel/concatjs ...
nodejs_binary(
    name = "tsc_wrapped-bin",
    # Point bazel to your node_modules to find the entry point
    data = [
        "@tools_npm//@bazel/concatjs",
        "@tools_npm//typescript",
    ],
    # It seems, bazel uses different approaches to compile ts files (it runs some
    # ts service in background). It works without any workaround.
    entry_point = "@tools_npm//:node_modules/@bazel/concatjs/internal/tsc_wrapped/tsc_wrapped.js",
)

# Wrap a typescript into a tsc-bin binary.
# The tsc-bin can be used as a tool to compile typescript code.
nodejs_binary(
    name = "tsc-bin",
    # Point bazel to your node_modules to find the entry point
    data = ["@tools_npm//typescript"],
    entry_point = "@tools_npm//:node_modules/typescript/lib/tsc.js",
)
