package io.github.christian8799.thatsaban;

import java.net.InetAddress;

interface CountryFilterIntegration {
    enum Result { ALLOWED, DENIED, UNAVAILABLE }
    boolean isAvailable();
    Result check(InetAddress address);
    void close();
}