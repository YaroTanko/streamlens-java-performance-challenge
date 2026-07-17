package com.streamlens.analyzer;

/** A user and its exact sequential value sum inside one aggregate group. */
public record TopUser(String userId, double value) {
}
