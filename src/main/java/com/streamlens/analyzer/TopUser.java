package com.streamlens.analyzer;

/** One user's sequential value total within an aggregate group. */
public record TopUser(String userId, double value) {}
