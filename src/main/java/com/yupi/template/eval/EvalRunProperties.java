package com.yupi.template.eval;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local evaluation runner configuration.
 */
@Data
@ConfigurationProperties(prefix = "article.eval")
public class EvalRunProperties {

    /**
     * Enable local eval runner on application startup.
     */
    private boolean enabled = false;

    /**
     * Directory containing eval case json files.
     */
    private String casesDir = "eval/cases";

    /**
     * Directory for eval run outputs.
     */
    private String runsDir = "eval/runs";

    /**
     * Which local user account to use when creating eval tasks.
     */
    private String userAccount = "admin";

    /**
     * Poll interval when waiting task state transitions.
     */
    private long pollIntervalMs = 3000L;

    /**
     * Max wait time for each stage transition.
     */
    private long timeoutMs = 600000L;
}
