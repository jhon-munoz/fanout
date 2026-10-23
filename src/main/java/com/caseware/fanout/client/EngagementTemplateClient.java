package com.caseware.fanout.client;

import com.caseware.fanout.exception.DownstreamException;
import com.caseware.fanout.model.EngagementRef;
import com.caseware.fanout.model.TemplateRef;

/**
 * The slow downstream owned by the engagement team. A call loads the engagement and takes about a minute.
 *
 * <p>Contract: implementations must enforce their own timeout and report it as {@code TRANSIENT}, and
 * the timeout must be shorter than the lease TTL. {@code THROTTLED} means the downstream asked us to back off.
 * {@code PERMANENT} means retrying cannot help (engagement deleted, corrupt, not found).
 */
public interface EngagementTemplateClient {

    TemplateRef loadTemplateRef(EngagementRef engagement) throws DownstreamException;
}
