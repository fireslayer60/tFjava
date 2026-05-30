package io.testkit.mock;

import com.github.tomakehurst.wiremock.WireMockServer;

/** Lifecycle handle for a running WireMock server. */
public final class ManagedMockServer implements AutoCloseable {

    private final WireMockServer server;
    private final String baseUrl;

    ManagedMockServer(WireMockServer server, String baseUrl) {
        this.server  = server;
        this.baseUrl = baseUrl;
    }

    public String baseUrl() { return baseUrl; }
    public int port()       { return server.port(); }

    public void resetStubs() {
        server.resetAll();
    }

    public void stop() {
        if (server.isRunning()) server.stop();
    }

    @Override
    public void close() { stop(); }
}
