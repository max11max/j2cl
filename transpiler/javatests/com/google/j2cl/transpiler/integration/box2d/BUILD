package(default_visibility = ["//transpiler/javatests/com/google/j2cl/transpiler/integration:__subpackages__"])

licenses(["notice"])  # Apache 2.0

load("//build_defs:rules.bzl", "j2cl_library")

gwt_module(
    name = "box2d_gwt",
    srcs = glob(["*.java"]),
    deps = ["//third_party/java/jbox2d:jbox2d_gwt"],
)

j2cl_library(
    name = "box2d_j2cl",
    srcs = glob(["*.java"]),
    deps = ["//third_party/java/jbox2d:jbox2d_j2cl"],
)
