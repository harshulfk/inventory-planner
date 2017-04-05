package fk.retail.ip.requirement.internal.command;

import fk.retail.ip.fdp.model.BatchFdpRequirementEventEntityPayload;

/**
 * Created by yogeshwari.k on 24/03/17.
 */
public interface FdpIngestor<T> {
    void pushToFdp(T requests);
}
