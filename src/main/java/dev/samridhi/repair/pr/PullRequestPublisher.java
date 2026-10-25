package dev.samridhi.repair.pr;

import dev.samridhi.repair.agent.RepairRun;
import java.io.IOException;

/** Turns a verified run into something a human can review. */
public interface PullRequestPublisher {
    record Result(String url, String note) {}

    Result publish(RepairRun run) throws IOException;
}
