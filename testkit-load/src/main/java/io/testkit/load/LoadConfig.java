package io.testkit.load;

import java.time.Duration;

/** Configuration for a single load test phase. */
public final class LoadConfig {

    private final int virtualUsers;
    private final int targetRps;           // 0 means "as fast as possible"
    private final Duration rampUp;
    private final Duration duration;
    private final Duration coolDown;

    private LoadConfig(Builder b) {
        this.virtualUsers = b.virtualUsers;
        this.targetRps    = b.targetRps;
        this.rampUp       = b.rampUp;
        this.duration     = b.duration;
        this.coolDown     = b.coolDown;
    }

    public static Builder builder() { return new Builder(); }

    public int virtualUsers()   { return virtualUsers; }
    public int targetRps()      { return targetRps; }
    public Duration rampUp()    { return rampUp; }
    public Duration duration()  { return duration; }
    public Duration coolDown()  { return coolDown; }

    public static final class Builder {
        private int virtualUsers = 10;
        private int targetRps    = 0;
        private Duration rampUp  = Duration.ZERO;
        private Duration duration = Duration.ofSeconds(30);
        private Duration coolDown = Duration.ofSeconds(5);

        /** Number of concurrent virtual users. */
        public Builder virtualUsers(int vu)    { this.virtualUsers = vu; return this; }

        /** Target requests per second (token-bucket throttling). 0 = unlimited. */
        public Builder rps(int rps)            { this.targetRps = rps; return this; }

        /** Ramp-up period before full load. */
        public Builder rampUp(Duration d)      { this.rampUp = d; return this; }

        /** How long to sustain the load. */
        public Builder duration(Duration d)    { this.duration = d; return this; }
        public Builder duration(long secs)     { return duration(Duration.ofSeconds(secs)); }

        /** Cool-down period at the end. */
        public Builder coolDown(Duration d)    { this.coolDown = d; return this; }

        public LoadConfig build() { return new LoadConfig(this); }
    }
}
