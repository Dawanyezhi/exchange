package com.laser.exchange.resultpublisher.archive;

public final class ArchiveResultLogReaderConfig {

    public static final String DEFAULT_RESULT_CHANNEL = "aeron:ipc";

    public static final int DEFAULT_RESULT_STREAM_ID = 1001;

    public static final String DEFAULT_REPLAY_CHANNEL = "aeron:ipc";

    public static final int DEFAULT_REPLAY_STREAM_ID = 1101;

    public static final int DEFAULT_FRAGMENT_LIMIT = 100;

    public static final int DEFAULT_IDLE_SPIN_LIMIT = 10_000;

    private final String resultChannel;

    private final int resultStreamId;

    private final String replayChannel;

    private final int replayStreamId;

    private final int fragmentLimit;

    private final int idleSpinLimit;

    public ArchiveResultLogReaderConfig(String resultChannel,
                                        int resultStreamId,
                                        String replayChannel,
                                        int replayStreamId,
                                        int fragmentLimit,
                                        int idleSpinLimit) {
        if (resultChannel == null || resultChannel.isBlank()) {
            throw new IllegalArgumentException("resultChannel must not be blank");
        }
        if (replayChannel == null || replayChannel.isBlank()) {
            throw new IllegalArgumentException("replayChannel must not be blank");
        }
        if (fragmentLimit <= 0) {
            throw new IllegalArgumentException("fragmentLimit must be positive");
        }
        if (idleSpinLimit <= 0) {
            throw new IllegalArgumentException("idleSpinLimit must be positive");
        }
        this.resultChannel = resultChannel;
        this.resultStreamId = resultStreamId;
        this.replayChannel = replayChannel;
        this.replayStreamId = replayStreamId;
        this.fragmentLimit = fragmentLimit;
        this.idleSpinLimit = idleSpinLimit;
    }

    public static ArchiveResultLogReaderConfig defaults() {
        return new ArchiveResultLogReaderConfig(
                DEFAULT_RESULT_CHANNEL,
                DEFAULT_RESULT_STREAM_ID,
                DEFAULT_REPLAY_CHANNEL,
                DEFAULT_REPLAY_STREAM_ID,
                DEFAULT_FRAGMENT_LIMIT,
                DEFAULT_IDLE_SPIN_LIMIT
        );
    }

    public String resultChannel() {
        return resultChannel;
    }

    public int resultStreamId() {
        return resultStreamId;
    }

    public String replayChannel() {
        return replayChannel;
    }

    public int replayStreamId() {
        return replayStreamId;
    }

    public int fragmentLimit() {
        return fragmentLimit;
    }

    public int idleSpinLimit() {
        return idleSpinLimit;
    }
}
